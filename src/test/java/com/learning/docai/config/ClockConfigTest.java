package com.learning.docai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ClockConfigTest {

    @Test
    void clockUsesViennaTime() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(ZoneId.of("Europe/Vienna"));
    }
}
