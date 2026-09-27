package com.learning.docai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * SPEC §8: the ollama profile must start without a reachable Ollama instance
 * (no eager connection, no model pull).
 */
@SpringBootTest
@ActiveProfiles("ollama")
class OllamaProfileStartupTest {

    @Autowired
    private Environment environment;

    @Autowired
    private Clock clock;

    @Test
    void startsWithOllamaProfileWithoutARunningOllama() {
        assertThat(environment.getProperty("spring.ai.ollama.chat.options.model"))
                .isEqualTo("qwen2.5vl:7b");
        assertThat(environment.getProperty("spring.ai.ollama.init.pull-model-strategy"))
                .isEqualTo("never");
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("ollama");
    }

    @Test
    void providesAViennaClock() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Europe/Vienna"));
    }
}
