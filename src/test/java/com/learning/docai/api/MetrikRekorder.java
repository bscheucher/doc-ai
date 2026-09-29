package com.learning.docai.api;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A {@link DocAiMetrics} over an in-memory registry, so a controller test can assert the meters
 * of SPEC §10 instead of only satisfying the constructor.
 *
 * <p>Every reader returns 0 for a meter that was never touched, which is what "not recorded"
 * looks like to a test: Micrometer only creates a series on first use.
 */
public final class MetrikRekorder {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DocAiMetrics metrics = new DocAiMetrics(registry);

    public DocAiMetrics metrics() {
        return metrics;
    }

    /** Value of `docai.requests` for one endpoint and outcome (ok / review / error). */
    public double anfragen(ApiEndpoint endpoint, String outcome) {
        Counter counter = registry.find("docai.requests")
                .tag("endpoint", endpoint.tag())
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** Number of recorded `docai.model.call` samples for one endpoint. */
    public long modellaufrufe(ApiEndpoint endpoint) {
        Timer timer = registry.find("docai.model.call").tag("endpoint", endpoint.tag()).timer();
        return timer == null ? 0 : timer.count();
    }

    /** Milliseconds of the single recorded `docai.model.call` sample for one endpoint. */
    public double modelldauerMs(ApiEndpoint endpoint) {
        Timer timer = registry.find("docai.model.call").tag("endpoint", endpoint.tag()).timer();
        return timer == null ? 0 : timer.totalTime(TimeUnit.MILLISECONDS);
    }

    /** Value of `docai.model.tokens` in one direction (input / output), across all models. */
    public double tokens(String direction) {
        return registry.find("docai.model.tokens").tag("direction", direction).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** True when no `docai.model.tokens` series exists at all - an unreported count. */
    public boolean ohneTokenmessung() {
        return registry.find("docai.model.tokens").counters().isEmpty();
    }
}
