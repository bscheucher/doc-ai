package com.learning.docai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Thresholds of the deterministic rules per SPEC §4 and §8. They decide when a plausible
 * value is still worth a second look, so they are configuration, not constants in a rule.
 */
@ConfigurationProperties(prefix = "docai.validation")
public record ValidationProperties(
        @DefaultValue("90") int maxDaysInPast,
        @DefaultValue("14") int maxDaysInFuture,
        @DefaultValue("60") int maxKrankenstandDays) {
}
