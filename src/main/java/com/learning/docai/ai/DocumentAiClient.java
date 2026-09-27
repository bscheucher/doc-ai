package com.learning.docai.ai;

import com.learning.docai.intake.PageImages;

/**
 * The only way business code reaches a model (SPEC §2 step 2). Deliberately provider-free:
 * no Spring AI or vendor type appears in the signature, so a feature service can be tested
 * without a model and the provider can be swapped by profile alone.
 */
public interface DocumentAiClient {

    /**
     * Sends the shared system prompt, the endpoint-specific instruction and every page image
     * in one call, and maps the answer onto {@code type}.
     *
     * @throws com.learning.docai.api.DocAiException with a model error type from SPEC §6 when
     *         the provider fails, times out, or its output cannot be mapped after the
     *         configured retry
     */
    <T> AiResult<T> extract(String instruction, PageImages pages, Class<T> type);
}
