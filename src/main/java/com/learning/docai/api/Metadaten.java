package com.learning.docai.api;

/**
 * Per-request model metadata, shared by all endpoints (SPEC §3.1).
 * Token counts are null when the provider does not report them.
 */
public record Metadaten(
        String provider,
        String modell,
        int seiten,
        Integer inputTokens,
        Integer outputTokens,
        long dauerMs) {
}
