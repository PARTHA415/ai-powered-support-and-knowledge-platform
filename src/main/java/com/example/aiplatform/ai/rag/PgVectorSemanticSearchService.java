package com.example.aiplatform.ai.rag;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.model.SimilarChunk;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.repository.DocumentChunkRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The single place every retrieval path in this application goes through -
 * {@code /api/qa}, the agent workflow's knowledge-base step, and the MCP-
 * exposed {@code searchKnowledgeBase} tool all call {@link #search} - which
 * is why "vector-search latency" and "retrieved document count" are
 * instrumented here rather than in each caller (Phase 14): one measurement
 * point, inherited by every feature that retrieves.
 *
 * Phase 16: the whole method - embedding generation AND the pgvector query -
 * sits behind the {@code vectorSearch} circuit breaker, not just
 * {@code findNearest(...)} in isolation. A finer split would need the
 * pgvector call in its own Spring bean (self-invocation bypasses the AOP
 * proxy Resilience4j's annotation relies on) purely to get separate
 * breaker identities - not worth the extra class for this application's
 * size. The practical effect: an embedding-provider outage and a Postgres
 * outage both count against the same breaker and both degrade the same way
 * (see {@link #searchFallback}) - a reasonable simplification, not a
 * silent gap.
 */
@Service
public class PgVectorSemanticSearchService implements SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSemanticSearchService.class);

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository documentChunkRepository;
    private final AiPipelineMetrics aiPipelineMetrics;

    public PgVectorSemanticSearchService(EmbeddingService embeddingService,
                                          DocumentChunkRepository documentChunkRepository,
                                          AiPipelineMetrics aiPipelineMetrics) {
        this.embeddingService = embeddingService;
        this.documentChunkRepository = documentChunkRepository;
        this.aiPipelineMetrics = aiPipelineMetrics;
    }

    @Override
    @CircuitBreaker(name = "vectorSearch", fallbackMethod = "searchFallback")
    public List<SemanticSearchResult> search(String query, int limit) {
        // Embedding generation is deliberately NOT included in the timed
        // block below - Spring AI's own observability already measures
        // embeddingService.embed(...) (gen_ai.client.operation.duration).
        // Timing only findNearest(...) isolates the pgvector query itself.
        float[] queryEmbedding = embeddingService.embed(query);
        List<SimilarChunk> nearest = aiPipelineMetrics.timeVectorSearch(
                () -> documentChunkRepository.findNearest(queryEmbedding, limit));
        aiPipelineMetrics.recordRetrievedDocuments(nearest.size());
        return nearest.stream()
                .map(chunk -> new SemanticSearchResult(chunk.documentTitle(), chunk.content(), 1 - chunk.distance()))
                .toList();
    }

    /**
     * "No relevant documentation was found" (an empty result list) rather
     * than a hard failure - retrieval going down should degrade a RAG
     * answer to "I don't have enough information," which
     * {@code QuestionAnsweringServiceImpl} and {@code AgentServiceImpl}
     * already render correctly for zero results, not take the whole request
     * down. Graceful degradation reusing an existing code path, not new
     * fallback logic of its own.
     */
    private List<SemanticSearchResult> searchFallback(String query, int limit, Throwable cause) {
        log.error("Vector search circuit breaker fallback triggered - retrieval is degraded to zero results", cause);
        return List.of();
    }
}
