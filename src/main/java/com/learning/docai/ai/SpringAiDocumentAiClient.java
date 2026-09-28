package com.learning.docai.ai;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.config.AiProperties;
import com.learning.docai.intake.PageImages;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The only class that knows Spring AI (SPEC §2 step 2). One call per request: shared system
 * prompt, endpoint instruction, every page image, structured output onto the endpoint's record.
 *
 * <p>The provider is whichever {@link ChatModel} the active profile put in the context; model
 * name and temperature come from `spring.ai.*`, never from here (SPEC §8).
 */
@Slf4j
@Component
public class SpringAiDocumentAiClient implements DocumentAiClient {

    private final ChatClient chatClient;
    private final AiProperties properties;
    private final String systemPrompt;
    private final String provider;
    private final String configuredModel;

    /**
     * Each call is run on its own virtual thread purely so it can be abandoned on timeout;
     * see.
     */
    private final ExecutorService modelCalls = Executors.newVirtualThreadPerTaskExecutor();

    public SpringAiDocumentAiClient(ChatModel chatModel, AiProperties properties) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.systemPrompt = Prompts.load("system.txt");
        this.provider = providerOf(chatModel.getClass());
        this.configuredModel = modelOf(chatModel);
    }

    @PreDestroy
    void shutdown() {
        modelCalls.shutdownNow();
    }

    @Override
    public <T> AiResult<T> extract(String instruction, PageImages pages, Class<T> type) {
        if (pages == null || pages.pageCount() == 0) {
            throw new DocAiException(ErrorType.UNREADABLE_DOCUMENT);
        }
        List<Media> media = pages.pages().stream()
                .map(SpringAiDocumentAiClient::pngMedia)
                .toList();

        // SPEC §2: a mapping failure is retried once, because it is usually a transient
        // formatting slip rather than a document the model cannot read.
        int attempts = Math.max(0, properties.retriesOnMappingError()) + 1;

        // SPEC §6 promises the caller a 504 once `docai.ai.timeout` passes, so the timeout is
        // the budget for the whole request: the retry shares the deadline instead of starting
        // a second one, which would let a slow model hold the caller for twice as long.
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.timeout().toNanos();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ResponseEntity<ChatResponse, T> response =
                        callWithin(deadline, () -> chatClient.prompt()
                                .system(systemPrompt)
                                .user(user -> user.text(instruction).media(media.toArray(Media[]::new)))
                                .call()
                                .responseEntity(type));

                T value = response.entity();
                if (value == null) {
                    throw new NotMappableException("Model returned no mappable content");
                }
                // Measured from the first attempt: a retry is time the caller waited too, and
                // dauerMs is what the SPEC §10 timer reports.
                return toResult(value, response.response(), millisSince(startedAt));
            } catch (RuntimeException e) {
                if (!isMappingFailure(e)) {
                    throw e;
                }
                log.warn("Model output not mappable to {} ({}), attempt {}/{}",
                        type.getSimpleName(), e.getClass().getSimpleName(), attempt, attempts);
            }
        }
        // Deliberately without a cause: Jackson quotes the unparsable model output in its
        // message, and that text is document content (CLAUDE.md hard rules). Anything that
        // logged this exception with its stack would leak it.
        throw new DocAiException(ErrorType.MODEL_ERROR);
    }

    /**
     * Spring AI has no per-call timeout, so the call is run on another thread and abandoned
     * when the request's deadline passes (SPEC §6, 504). {@code deadlineNanos} is a
     * {@link System#nanoTime()} reading shared by every attempt, so a retry only gets what is
     * left of {@code docai.ai.timeout}.
     *
     * <p>Cancelling interrupts that thread, which does not necessarily abort a socket read
     * already in flight: the provider request may run on in the background until the HTTP
     * client gives up. The caller is answered on time either way, and a timeout is never
     * retried, so at worst we pay for one response nobody reads.
     */
    private <R> R callWithin(long deadlineNanos, Callable<R> call) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            log.warn("Model call budget of {} spent before the next attempt", properties.timeout());
            throw new DocAiException(ErrorType.MODEL_TIMEOUT);
        }
        Future<R> future = modelCalls.submit(call);
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Model call timed out after {}", properties.timeout());
            throw new DocAiException(ErrorType.MODEL_TIMEOUT, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DocAiException(ErrorType.INTERNAL_ERROR, e);
        } catch (ExecutionException e) {
            throw asDocAiException(e.getCause());
        }
    }

    /**
     * Provider failures per SPEC §6. A mapping failure is let through unchanged so the retry
     * loop can see it; everything else becomes its final status here.
     */
    private RuntimeException asDocAiException(Throwable cause) {
        if (cause instanceof DocAiException docAi) {
            return docAi;
        }
        if (cause instanceof RuntimeException runtime && isMappingFailure(runtime)) {
            return runtime;
        }
        if (cause instanceof TransientAiException || hasCause(cause, IOException.class)) {
            // Rate limited, 5xx from the provider, or unreachable: worth trying again later.
            log.warn("Model provider unavailable: {}", cause.getClass().getSimpleName());
            return new DocAiException(ErrorType.MODEL_UNAVAILABLE, cause);
        }
        if (cause instanceof NonTransientAiException) {
            log.warn("Model provider rejected the call: {}", cause.getClass().getSimpleName());
            return new DocAiException(ErrorType.MODEL_ERROR, cause);
        }
        log.warn("Model call failed: {}", cause.getClass().getName());
        return new DocAiException(ErrorType.MODEL_ERROR, cause);
    }

    /** A well-formed answer that carried no content - retryable, like a parse failure. */
    @SuppressWarnings("serial")
    private static final class NotMappableException extends RuntimeException {
        NotMappableException(String message) {
            super(message);
        }
    }

    /**
     * Structured output surfaces unparsable output as a plain {@code RuntimeException} or
     * {@code IllegalStateException} wrapping Jackson's failure, so the cause chain - not the
     * exception type - is what identifies it. Catching {@code RuntimeException} broadly here
     * would turn real bugs into 502s and retry them.
     */
    private static boolean isMappingFailure(Throwable candidate) {
        if (candidate instanceof DocAiException) {
            return false;
        }
        return candidate instanceof NotMappableException
                || hasCause(candidate, JsonProcessingException.class);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private <T> AiResult<T> toResult(T value, ChatResponse response, long durationMs) {
        Integer inputTokens = null;
        Integer outputTokens = null;
        String model = configuredModel;

        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                inputTokens = reportedOrNull(usage.getPromptTokens());
                outputTokens = reportedOrNull(usage.getCompletionTokens());
            }
            String reported = response.getMetadata().getModel();
            if (reported != null && !reported.isBlank()) {
                model = reported;
            }
        }
        log.debug("Model call ok: provider={} model={} inputTokens={} outputTokens={} dauerMs={}",
                provider, model, inputTokens, outputTokens, durationMs);
        return new AiResult<>(value, provider, model, inputTokens, outputTokens, durationMs);
    }

    /**
     * Spring AI fills in an {@code EmptyUsage} reporting 0 when a provider sends no token
     * counts - Ollama does not - so 0 and "unknown" arrive identically. A successful call
     * cannot really have cost 0 tokens, so 0 is reported as absent rather than as a figure
     * that would quietly skew the token metric (SPEC §10).
     */
    private static Integer reportedOrNull(Integer tokens) {
        return tokens == null || tokens == 0 ? null : tokens;
    }

    private static Media pngMedia(byte[] png) {
        // Intake normalises every page to PNG, so the mime type is not guesswork.
        return new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(png));
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * {@code AnthropicChatModel} -> {@code anthropic}: the tag follows whichever provider the
     * profile selected, with nothing to keep in sync by hand. Package-private so the mapping
     * can be asserted against the real provider classes - it reaches both the API response
     * and the metric tags of SPEC §10.
     */
    static String providerOf(Class<? extends ChatModel> chatModelClass) {
        String name = chatModelClass.getSimpleName();
        int suffix = name.indexOf("ChatModel");
        return (suffix > 0 ? name.substring(0, suffix) : name).toLowerCase(Locale.ROOT);
    }

    /** Fallback only: the response normally reports the model it actually used. */
    private static String modelOf(ChatModel chatModel) {
        return chatModel.getDefaultOptions() != null && chatModel.getDefaultOptions().getModel() != null
                ? chatModel.getDefaultOptions().getModel()
                : "unbekannt";
    }
}
