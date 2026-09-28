package com.learning.docai.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CLAUDE.md hard rule: document content is never logged. Spring AI's structured-output
 * converter logs the model's full raw answer at ERROR when it cannot map it - names, SVNR,
 * Krankenstandsadresse included - and the retry makes that happen twice per request, so the
 * logger is silenced in application.yml.
 */
@SpringBootTest
class ModelOutputLoggingTest {

    @Test
    void keepsSpringAisRawOutputLoggerSilent() {
        assertThat(LoggerFactory.getLogger(BeanOutputConverter.class).isErrorEnabled()).isFalse();
    }
}
