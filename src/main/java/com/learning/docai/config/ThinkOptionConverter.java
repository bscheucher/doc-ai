package com.learning.docai.config;

import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Makes {@code spring.ai.ollama.chat.options.think-option} settable in application.yml.
 *
 * <p>Ollama's reasoning models spend most of their output on reasoning, which this service has no
 * use for: extraction wants the JSON, and measured on a Krankenstandsbestaetigung the same page
 * takes 2.8 s with reasoning off against 15.4 s with it on, for a byte-identical answer. With it
 * on, a classification spends 431-829 output tokens where claude-sonnet-5 spends 96-110, and the
 * larger documents run past {@code docai.ai.timeout}.
 *
 * <p>Spring AI 1.1.x carries the flag as {@code thinkOption} on {@code OllamaChatOptions}, but
 * {@link ThinkOption} is a sealed interface whose two implementations are read by custom Jackson
 * (de)serialisers. The configuration binder does not use Jackson, so without a converter it fails
 * at startup with {@code No converter found capable of converting from type [java.lang.String] to
 * type [ThinkOption]} - the property cannot be set at all. This is that converter, and nothing
 * more: leaving the property out leaves the model's own default in place.
 *
 * <p>Accepts {@code true}/{@code false} and the three levels {@code low}, {@code medium},
 * {@code high}, which is the whole of what the two implementations express.
 */
@Component
@ConfigurationPropertiesBinding
public class ThinkOptionConverter implements Converter<String, ThinkOption> {

    @Override
    public ThinkOption convert(String source) {
        String wert = source.trim().toLowerCase();
        return switch (wert) {
            case "true" -> ThinkOption.ThinkBoolean.ENABLED;
            case "false" -> ThinkOption.ThinkBoolean.DISABLED;
            case "low" -> ThinkOption.ThinkLevel.LOW;
            case "medium" -> ThinkOption.ThinkLevel.MEDIUM;
            case "high" -> ThinkOption.ThinkLevel.HIGH;
            default -> throw new IllegalArgumentException(
                    "Invalid think-option '" + source
                            + "': expected true, false, low, medium or high");
        };
    }
}
