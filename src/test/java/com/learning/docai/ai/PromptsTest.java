package com.learning.docai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Spring AI renders prompt text as a template, so a curly brace in a prompt is a production
 * failure on a document we are not allowed to log (CLAUDE.md, SPEC §2). It must fail here.
 */
class PromptsTest {

    @Test
    void loadsTheSharedSystemPrompt() {
        assertThat(Prompts.load("system.txt"))
                .contains("Dokumentenpruefer")
                .contains("Diagnosen");
    }

    @Test
    void loadsTheKlassifikationInstruction() {
        assertThat(Prompts.load("klassifikation.txt"))
                .contains("KRANKENSTANDSBESTAETIGUNG")
                .contains("ZEITBESTAETIGUNG")
                .contains("UNBEKANNT");
    }

    @Test
    void noShippedPromptContainsCurlyBraces() throws Exception {
        Path prompts = Path.of("src/main/resources/prompts");
        try (var files = Files.list(prompts)) {
            List<Path> texts = files.filter(path -> path.toString().endsWith(".txt")).toList();

            assertThat(texts).isNotEmpty();
            for (Path text : texts) {
                assertThat(Files.readString(text))
                        .as("prompt %s", text.getFileName())
                        .doesNotContain("{")
                        .doesNotContain("}");
            }
        }
    }

    @Test
    void failsOnAMissingPrompt() {
        assertThatThrownBy(() -> Prompts.load("gibt-es-nicht.txt"))
                .isInstanceOf(UncheckedIOException.class);
    }
}
