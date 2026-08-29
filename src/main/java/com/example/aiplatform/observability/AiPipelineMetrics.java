package com.example.aiplatform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Hand-instrumented business metrics for the parts of the AI pipeline
 * Spring AI does NOT already measure automatically. Everything else -
 * LLM latency/token-usage/model ({@code gen_ai.client.operation.duration},
 * {@code gen_ai.client.token.usage}), embedding latency (same
 * {@code gen_ai.client.operation.duration} family, tagged by operation
 * name), and tool-call latency ({@code spring.ai.tool}) - comes for free
 * from Spring AI's own Micrometer {@code Observation} instrumentation the
 * moment a {@link MeterRegistry} bean exists (i.e. once
 * spring-boot-starter-actuator + micrometer-registry-prometheus are on the
 * classpath), and likewise REST-layer latency/request-count/failures come
 * for free from Spring Boot's {@code http.server.requests} timer. This
 * class exists only for the remainder: the hand-written pgvector query
 * (Spring AI has no visibility into a repository we wrote ourselves) and
 * agent-workflow-specific counts that have no generic equivalent to piggy-
 * back on.
 *
 * Meters are built and registered inline on every call rather than cached
 * as fields - safe and idiomatic for Micrometer, whose registries
 * deduplicate by name+tags internally, and necessary anyway for
 * {@link #recordSafetyBoundTriggered(String)} since its tag value varies
 * per call (a distinct {@code Counter} identity per {@code reason}).
 */
@Component
public class AiPipelineMetrics {

    private final MeterRegistry meterRegistry;

    public AiPipelineMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Times the hand-written pgvector nearest-neighbor query specifically -
     * deliberately excludes embedding generation (already measured by
     * Spring AI's own instrumentation), so this metric answers "how long did
     * the vector search itself take", not "how long did retrieval take
     * overall".
     */
    public <T> T timeVectorSearch(Supplier<T> search) {
        return Timer.builder("rag.vector.search")
                .description("Latency of the pgvector nearest-neighbor query, excluding embedding generation")
                .register(meterRegistry)
                .record(search);
    }

    /** How many chunks a single vector-search call returned, before similarity-threshold filtering. */
    public void recordRetrievedDocuments(int count) {
        DistributionSummary.builder("rag.retrieved.documents")
                .description("Number of chunks returned by a single vector-search call")
                .register(meterRegistry)
                .record(count);
    }

    /**
     * How many of a hybrid search's candidates the full-text half found and the
     * vector half did not.
     *
     * <p>This is the metric that answers "is hybrid retrieval earning its
     * keep". If it sits at zero, the lexical search is contributing nothing the
     * vector search was not already finding, and the extra query is pure cost;
     * if it is consistently non-zero, those are results dense-only retrieval
     * was silently missing. Either reading is actionable, which is more than
     * could be said for adding the feature and assuming it helped.
     */
    public void recordLexicalOnlyCandidates(int count) {
        DistributionSummary.builder("rag.hybrid.lexical.only")
                .description("Candidates found only by the full-text search, not by the vector search")
                .register(meterRegistry)
                .record(count);
    }

    /**
     * Whether a question was answered from the semantic cache or had to be
     * computed. Tagged rather than two counters so one panel shows the hit rate
     * and can be broken down; the hit rate is the number that says whether the
     * cache is worth its risk, and the risk is real (see
     * {@link com.example.aiplatform.ai.rag.SemanticAnswerCache}).
     */
    public void recordSemanticCacheOutcome(String outcome) {
        Counter.builder("ai.answer.cache")
                .description("Semantic answer cache hits and misses")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** How many capability iterations one agent request actually executed. */
    public void recordAgentIterations(int iterations) {
        DistributionSummary.builder("agent.iterations")
                .description("Number of capability iterations executed per agent request")
                .register(meterRegistry)
                .record(iterations);
    }

    /**
     * A protective limit fired - an AI guardrail (Phase 12) or an agent
     * safety bound (Phase 9) blocked, truncated, or capped a request.
     * Deliberately one counter family tagged by {@code reason} rather than
     * N separately-named counters, so a single Grafana panel can show all
     * safety-bound activity broken down by cause, or be filtered to one.
     *
     * Wired into only the handful of classes that are neither directly
     * {@code new}'d across many unrelated unit tests nor auto-included by
     * {@code @WebMvcTest} slice scanning ({@link com.example.aiplatform.service.AgentServiceImpl},
     * {@link com.example.aiplatform.ai.llm.SpringAiLlmClientService}) - see
     * the Phase 14 docs for why the other guardrail classes were left alone
     * despite also being reasonable candidates.
     */
    public void recordSafetyBoundTriggered(String reason) {
        Counter.builder("ai.safety.bound.triggered")
                .description("A guardrail or agent safety bound fired and blocked/truncated a request")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}
