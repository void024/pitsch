package com.pitsch.backend.observability;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Business and reliability metrics exported at /actuator/prometheus (management port). */
@Component
public class PitschMetrics {

    private final MeterRegistry registry;
    private final AtomicLong queueDepth = new AtomicLong();

    public PitschMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("pitsch.jobs.queue.depth", queueDepth, AtomicLong::get)
                .description("Jobs waiting to run").register(registry);
    }

    public void agentCall(String agent, String status, long latencyMs, long promptTokens, long completionTokens,
                          BigDecimal costUsd) {
        Timer.builder("pitsch.agent.duration").tag("agent", agent).tag("status", status)
                .publishPercentileHistogram().register(registry).record(Duration.ofMillis(Math.max(0, latencyMs)));
        Counter.builder("pitsch.llm.tokens").tag("agent", agent).tag("kind", "prompt").register(registry)
                .increment(promptTokens);
        Counter.builder("pitsch.llm.tokens").tag("agent", agent).tag("kind", "completion").register(registry)
                .increment(completionTokens);
        if (costUsd != null) {
            Counter.builder("pitsch.llm.cost.usd").tag("agent", agent).register(registry).increment(costUsd.doubleValue());
        }
    }

    public void workflowFinished(String outcome, Duration duration) {
        Timer.builder("pitsch.workflow.duration").tag("outcome", outcome).register(registry).record(duration);
    }

    public void integrationCall(String provider, String operation, boolean success) {
        Counter.builder("pitsch.integration.calls").tag("provider", provider).tag("operation", operation)
                .tag("outcome", success ? "success" : "failure").register(registry).increment();
    }

    public void jobFinished(String type, String outcome) {
        Counter.builder("pitsch.jobs.finished").tag("type", type).tag("outcome", outcome).register(registry).increment();
    }

    public void setQueueDepth(long depth) {
        queueDepth.set(depth);
    }
}
