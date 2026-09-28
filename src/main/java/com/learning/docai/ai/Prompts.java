package com.learning.docai.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;

/**
 * Loads prompt texts from {@code src/main/resources/prompts/} so wording can be reviewed as
 * plain text rather than buried in Java string literals.
 *
 * <p>Curly braces are rejected on load: Spring AI renders prompt text as a template, so a
 * stray brace would either be substituted or blow up at call time, in production, on a
 * document we cannot log. Failing at startup instead is the whole point (SPEC §2, CLAUDE.md).
 */
public final class Prompts {

    private Prompts() {
    }

    public static String load(String name) {
        String path = "prompts/" + name;
        try {
            String text = new ClassPathResource(path)
                    .getContentAsString(StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                throw new IllegalStateException("Prompt is empty: " + path);
            }
            if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0) {
                throw new IllegalStateException(
                        "Prompt must not contain curly braces (Spring AI template): " + path);
            }
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt not readable: " + path, e);
        }
    }
}
