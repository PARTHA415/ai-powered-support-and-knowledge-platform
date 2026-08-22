package com.example.aiplatform.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiPipelineMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiPipelineMetrics metrics = new AiPipelineMetrics(meterRegistry);

    @Test
    void timeVectorSearchRecordsATimerAndReturnsTheSuppliersResult() {
        String result = metrics.timeVectorSearch(() -> "nearest chunks");

        assertThat(result).isEqualTo("nearest chunks");
        assertThat(meterRegistry.get("rag.vector.search").timer().count()).isEqualTo(1);
    }

    @Test
    void recordRetrievedDocumentsAddsToTheDistributionSummary() {
        metrics.recordRetrievedDocuments(3);
        metrics.recordRetrievedDocuments(5);

        assertThat(meterRegistry.get("rag.retrieved.documents").summary().count()).isEqualTo(2);
        assertThat(meterRegistry.get("rag.retrieved.documents").summary().totalAmount()).isEqualTo(8.0);
    }

    @Test
    void recordAgentIterationsAddsToTheDistributionSummary() {
        metrics.recordAgentIterations(2);

        assertThat(meterRegistry.get("agent.iterations").summary().totalAmount()).isEqualTo(2.0);
    }

    @Test
    void recordSafetyBoundTriggeredIncrementsACounterTaggedByReason() {
        metrics.recordSafetyBoundTriggered("prompt_too_large");
        metrics.recordSafetyBoundTriggered("prompt_too_large");
        metrics.recordSafetyBoundTriggered("agent_timeout");

        assertThat(meterRegistry.get("ai.safety.bound.triggered").tag("reason", "prompt_too_large").counter().count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.get("ai.safety.bound.triggered").tag("reason", "agent_timeout").counter().count())
                .isEqualTo(1.0);
    }
}
