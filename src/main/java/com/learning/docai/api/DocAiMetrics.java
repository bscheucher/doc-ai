package com.learning.docai.api;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/**
 * The three meters of SPEC §10. Tag values are bounded: {@link ApiEndpoint} for the endpoint,
 * a fixed vocabulary for `outcome` and `direction`, and provider and model as the active
 * profile reports them.
 *
 * <p>Nothing measured here is document content: counts, durations and token totals only
 * (CLAUDE.md hard rules).
 */
@RequiredArgsConstructor
@Component
public class DocAiMetrics {

    private static final String REQUESTS = "docai.requests";
    private static final String MODEL_CALL = "docai.model.call";
    private static final String MODEL_TOKENS = "docai.model.tokens";

    /** `outcome` per SPEC §10: a clean result, one a person has to look at, or a failure. */
    private static final String OUTCOME_OK = "ok";
    private static final String OUTCOME_REVIEW = "review";
    private static final String OUTCOME_ERROR = "error";

    private final MeterRegistry registry;

    /**
     * One finished request. `review` rather than `ok` whenever the caller was told to have a
     * person look at the document, which is `manuellePruefung` in every response (SPEC §3.1).
     */
    public void anfrage(ApiEndpoint endpoint, boolean manuellePruefung) {
        zaehleAnfrage(endpoint, manuellePruefung ? OUTCOME_REVIEW : OUTCOME_OK);
    }

    /**
     * One request that ended in a problem response (SPEC §6). Counted from the error handler,
     * so it covers the failures that never reach a service - an upload too large, a missing
     * part, an unsupported type - as well as the ones a model call raises.
     */
    public void fehler(ApiEndpoint endpoint) {
        zaehleAnfrage(endpoint, OUTCOME_ERROR);
    }

    /**
     * One model call: its duration, and the tokens it cost. Token counts are absent for
     * providers that do not report them (Ollama), and an absent count is not recorded at all
     * rather than recorded as zero, which would read as a measurement of zero.
     */
    public void modellaufruf(ApiEndpoint endpoint, String provider, String model,
            Integer inputTokens, Integer outputTokens, long dauerMs) {

        Timer.builder(MODEL_CALL)
                .tag("endpoint", endpoint.tag())
                .tag("provider", provider)
                .tag("model", model)
                .register(registry)
                .record(dauerMs, TimeUnit.MILLISECONDS);

        zaehleTokens("input", model, inputTokens);
        zaehleTokens("output", model, outputTokens);
    }

    private void zaehleAnfrage(ApiEndpoint endpoint, String outcome) {
        Counter.builder(REQUESTS)
                .tag("endpoint", endpoint.tag())
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    private void zaehleTokens(String direction, String model, Integer tokens) {
        if (tokens == null) {
            return;
        }
        Counter.builder(MODEL_TOKENS)
                .tag("direction", direction)
                .tag("model", model)
                .register(registry)
                .increment(tokens);
    }
}
