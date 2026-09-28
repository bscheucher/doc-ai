package com.learning.docai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import com.learning.docai.api.DocAiException;
import com.learning.docai.api.ErrorType;
import com.learning.docai.config.AiProperties;
import com.learning.docai.intake.PageImages;

/**
 * The provider-facing contract of SPEC §2 step 2 and §6: one call per request, all pages in
 * it, one retry on unmappable output, and every provider failure mapped to its own status.
 */
class SpringAiDocumentAiClientTest {

    private static final AiProperties PROPERTIES = new AiProperties(Duration.ofSeconds(5), 1);
    private static final PageImages TWO_PAGES =
            new PageImages(List.of(new byte[] { 1 }, new byte[] { 2 }));

    private final ChatModel chatModel = mock(ChatModel.class);

    private SpringAiDocumentAiClient client(AiProperties properties) {
        return new SpringAiDocumentAiClient(chatModel, properties);
    }

    @Test
    void mapsTheModelAnswerOntoTheRecord() {
        answerWith("""
                {"typ":"A","begruendung":"weil"}""", 111, 22);

        AiResult<Antwort> result = client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class);

        assertThat(result.value()).isEqualTo(new Antwort("A", "weil"));
        assertThat(result.inputTokens()).isEqualTo(111);
        assertThat(result.outputTokens()).isEqualTo(22);
        assertThat(result.model()).isEqualTo("test-modell");
        assertThat(result.durationMs()).isNotNegative();
    }

    @Test
    void sendsEveryPageInOneCall() {
        answerWith("""
                {"typ":"A","begruendung":"weil"}""", 1, 1);

        client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class);

        Prompt prompt = capturedPrompt();
        List<Media> media = prompt.getInstructions().stream()
                .filter(message -> message instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(message -> ((org.springframework.ai.chat.messages.UserMessage) message).getMedia())
                .flatMap(List::stream)
                .toList();

        verify(chatModel, times(1)).call(any(Prompt.class));
        assertThat(media).hasSize(2);
    }

    @Test
    void sendsTheSharedSystemPromptAndTheEndpointInstruction() {
        answerWith("""
                {"typ":"A","begruendung":"weil"}""", 1, 1);

        client(PROPERTIES).extract("KLASSIFIZIERE-DIES", TWO_PAGES, Antwort.class);

        String text = capturedPrompt().getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);

        assertThat(text).contains("Dokumentenpruefer").contains("KLASSIFIZIERE-DIES");
    }

    @Test
    void retriesOnceWhenTheOutputIsNotMappable() {
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
                response(calls.incrementAndGet() == 1 ? "kein JSON" : """
                        {"typ":"A","begruendung":"weil"}""", 1, 1));

        AiResult<Antwort> result = client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class);

        assertThat(result.value().typ()).isEqualTo("A");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void mapsUnmappableOutputAfterTheRetryTo502() {
        answerWith("kein JSON", 1, 1);

        assertThatThrownBy(() -> client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.MODEL_ERROR);

        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void honoursRetriesOnMappingErrorZero() {
        answerWith("kein JSON", 1, 1);

        assertThatThrownBy(() -> client(new AiProperties(Duration.ofSeconds(5), 0))
                .extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class);

        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void mapsATimeoutTo504() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return response("""
                    {"typ":"A","begruendung":"weil"}""", 1, 1);
        });

        assertThatThrownBy(() -> client(new AiProperties(Duration.ofMillis(100), 1))
                .extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.MODEL_TIMEOUT);
    }

    @Test
    void doesNotRetryATimeout() {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            return response("egal", 1, 1);
        });

        assertThatThrownBy(() -> client(new AiProperties(Duration.ofMillis(100), 1))
                .extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class);

        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void spendsOneTimeoutBudgetOnAllAttempts() {
        // Each attempt sleeps 300 ms and answers unmappably, so a per-attempt timeout would
        // hold the caller for two full budgets and answer 502 long after the promised 504.
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(300);
            return response("kein JSON", 1, 1);
        });

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> client(new AiProperties(Duration.ofMillis(500), 1))
                .extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.MODEL_TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofMillis(900));
    }

    @Test
    void reportsTheDurationTheCallerWaitedIncludingTheRetry() {
        AtomicInteger calls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                Thread.sleep(200);
                return response("kein JSON", 1, 1);
            }
            return response("""
                    {"typ":"A","begruendung":"weil"}""", 1, 1);
        });

        AiResult<Antwort> result = client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class);

        assertThat(result.durationMs()).isGreaterThanOrEqualTo(200);
    }

    @Test
    void keepsTheModelOutputOutOfTheFailure() {
        // Jackson quotes the text it could not parse; carrying that cause into the 502 would
        // put document content into any stack trace that is ever logged.
        answerWith("Diagnose: Grippe, Patient Mueller", 1, 1);

        assertThatThrownBy(() -> client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .hasNoCause()
                .hasMessageNotContaining("Grippe")
                .hasMessageNotContaining("Mueller");
    }

    @Test
    void mapsIsoDatesAndTimesOntoJavaTime() {
        // Endpoints 2 and 3 extract java.time values, and it is Spring AI's own ObjectMapper
        // that has to understand them - not the one Spring Boot configures for our responses.
        answerWith("""
                {"tag":"2026-03-15","zeit":"10:30:00"}""", 1, 1);

        Termin termin = client(PROPERTIES).extract("anweisung", TWO_PAGES, Termin.class).value();

        assertThat(termin.tag()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(termin.zeit()).isEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void acceptsATimeWithoutSeconds() {
        answerWith("""
                {"tag":"2026-03-15","zeit":"10:30"}""", 1, 1);

        assertThat(client(PROPERTIES).extract("anweisung", TWO_PAGES, Termin.class)
                .value().zeit()).isEqualTo(LocalTime.of(10, 30));
    }

    @Test
    void mapsATransientProviderFailureTo503() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("rate limited"));

        assertThatThrownBy(() -> client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.MODEL_UNAVAILABLE);
    }

    @Test
    void mapsAnUnreachableProviderTo503() {
        when(chatModel.call(any(Prompt.class))).thenThrow(
                new RuntimeException("connect", new IOException(new SocketTimeoutException())));

        assertThatThrownBy(() -> client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.MODEL_UNAVAILABLE);
    }

    @Test
    void mapsANonTransientProviderFailureTo502() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("bad request"));

        assertThatThrownBy(() -> client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.MODEL_ERROR);
    }

    @Test
    void doesNotRetryAProviderFailure() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new NonTransientAiException("bad request"));

        assertThatThrownBy(() -> client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class))
                .isInstanceOf(DocAiException.class);

        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void rejectsAnEmptyDocumentWithoutCallingTheModel() {
        assertThatThrownBy(() -> client(PROPERTIES)
                .extract("anweisung", new PageImages(List.of()), Antwort.class))
                .isInstanceOf(DocAiException.class)
                .extracting(e -> ((DocAiException) e).errorType())
                .isEqualTo(ErrorType.UNREADABLE_DOCUMENT);

        verify(chatModel, times(0)).call(any(Prompt.class));
    }

    @Test
    void leavesTokenCountsNullWhenTheProviderReportsNone() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("""
                        {"typ":"A","begruendung":"weil"}""")))));

        AiResult<Antwort> result = client(PROPERTIES).extract("anweisung", TWO_PAGES, Antwort.class);

        assertThat(result.inputTokens()).isNull();
        assertThat(result.outputTokens()).isNull();
    }

    private void answerWith(String content, int inputTokens, int outputTokens) {
        when(chatModel.call(any(Prompt.class)))
                .thenAnswer(invocation -> response(content, inputTokens, outputTokens));
    }

    private static ChatResponse response(String content, int inputTokens, int outputTokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))),
                ChatResponseMetadata.builder()
                        .model("test-modell")
                        .usage(new DefaultUsage(inputTokens, outputTokens))
                        .build());
    }

    private Prompt capturedPrompt() {
        org.mockito.ArgumentCaptor<Prompt> captor =
                org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, org.mockito.Mockito.atLeastOnce()).call(captor.capture());
        return captor.getValue();
    }

    @Test
    void derivesTheProviderTagFromTheConfiguredModel() {
        // The tag reaches the API response and the SPEC §10 metrics, so it must track the
        // profile rather than a hand-maintained string.
        assertThat(SpringAiDocumentAiClient.providerOf(
                org.springframework.ai.anthropic.AnthropicChatModel.class)).isEqualTo("anthropic");
        assertThat(SpringAiDocumentAiClient.providerOf(
                org.springframework.ai.ollama.OllamaChatModel.class)).isEqualTo("ollama");
    }

    /** Stand-in for a real extraction record: this test is about the client, not a schema. */
    record Antwort(String typ, String begruendung) {
    }

    /** The java.time shapes endpoints 2 and 3 extract (SPEC §3.3, §3.4). */
    record Termin(LocalDate tag, LocalTime zeit) {
    }
}
