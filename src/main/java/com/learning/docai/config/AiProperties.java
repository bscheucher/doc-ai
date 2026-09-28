package com.learning.docai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Model call limits per SPEC §8. The provider and the model name itself are Spring AI
 * configuration (`spring.ai.*`), selected by profile - never hard-coded here.
 */
@ConfigurationProperties(prefix = "docai.ai")
public record AiProperties(
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("1") int retriesOnMappingError) {
}
