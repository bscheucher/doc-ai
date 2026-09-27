package com.learning.docai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Intake limits per SPEC §2 and §5.
 */
@ConfigurationProperties(prefix = "docai.intake")
public record IntakeProperties(
        @DefaultValue("150") int renderDpi,
        @DefaultValue("1600") int maxImageEdge,
        @DefaultValue("5") int maxPages) {
}
