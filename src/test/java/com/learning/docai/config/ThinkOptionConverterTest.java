package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatProperties;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link ThinkOptionConverter} exists so that
 * {@code spring.ai.ollama.chat.options.think-option} can be set at all: without it the
 * application does not start, because the configuration binder has no way from a String to the
 * sealed {@link ThinkOption}.
 *
 * <p>Which is why the second part of this test matters more than the first. Converting a string
 * correctly is worth little if Spring never asks the converter - the annotation is what makes it
 * part of configuration binding, and only a context can show that it is.
 */
class ThinkOptionConverterTest {

    private final ThinkOptionConverter converter = new ThinkOptionConverter();

    @Test
    void mapsTrueAndFalseToTheBooleanOption() {
        assertThat(converter.convert("true")).isEqualTo(ThinkOption.ThinkBoolean.ENABLED);
        assertThat(converter.convert("false")).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    }

    @ParameterizedTest
    @CsvSource({ "low", "medium", "high" })
    void mapsEachLevelToTheLevelOption(String level) {
        assertThat(converter.convert(level)).isEqualTo(new ThinkOption.ThinkLevel(level));
    }

    /**
     * Relaxed binding hands over whatever the YAML said, and `False` or a trailing space in a
     * property file should not be the difference between a service that starts and one that does
     * not.
     */
    @ParameterizedTest
    @ValueSource(strings = { "FALSE", "False", " false", "false ", " FaLsE " })
    void ignoresCaseAndSurroundingSpace(String wert) {
        assertThat(converter.convert(wert)).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    }

    /**
     * A typo has to fail at startup naming the value, not silently leave the model reasoning:
     * the whole point of setting this is that reasoning costs a multiple of the latency.
     */
    @Test
    void rejectsAnythingElseAndSaysWhatWasAllowed() {
        assertThatThrownBy(() -> converter.convert("nein"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nein")
                .hasMessageContaining("true")
                .hasMessageContaining("high");
    }

    /**
     * The regression that matters: before the converter this context failed to start with
     * {@code No converter found capable of converting from type [java.lang.String] to type
     * [ThinkOption]}.
     */
    @Nested
    @SpringBootTest(properties = "spring.ai.ollama.chat.options.think-option=false")
    @ActiveProfiles("ollama")
    class BindingTest {

        @Autowired
        private OllamaChatProperties properties;

        @Test
        void bindsTheYamlPropertyOntoTheChatOptions() {
            assertThat(properties.getOptions().getThinkOption())
                    .isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
        }
    }
}
