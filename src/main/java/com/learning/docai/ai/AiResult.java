package com.learning.docai.ai;

import com.learning.docai.api.Metadaten;

/**
 * One model call: the mapped value plus what it cost (SPEC §3.1 `metadaten`).
 *
 * <p>Token counts are boxed because not every provider reports them - Ollama leaves them out.
 * A missing count stays null rather than being reported as 0, which a caller would read as a
 * measurement.
 *
 * @param <T> the endpoint's extraction record
 */
public record AiResult<T>(
        T value,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        long durationMs) {

    /**
     * The API-facing metadata for a response. Page count is the caller's, not the model's -
     * the client never sees how many pages were rendered.
     */
    public Metadaten metadaten(int seiten) {
        return new Metadaten(provider, model, seiten, inputTokens, outputTokens, durationMs);
    }
}
