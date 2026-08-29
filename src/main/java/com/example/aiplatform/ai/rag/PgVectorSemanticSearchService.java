package com.example.aiplatform.ai.rag;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.HybridChunkMatch;
import com.example.aiplatform.model.RetrievalFilter;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.model.SimilarChunk;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.repository.DocumentChunkRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The single place every retrieval path in this application goes through -
 * {@code /api/qa}, the agent workflow's knowledge-base step, and the MCP-
 * exposed {@code searchKnowledgeBase} tool all call {@link #search} - which
 * is why "vector-search latency" and "retrieved document count" are
 * instrumented here rather than in each caller: one measurement point,
 * inherited by every feature that retrieves. It is also why the audience
 * policy is applied here, where no caller can forget it.
 *
 * <h2>Hybrid retrieval</h2>
 *
 * Retrieval now runs two searches and fuses them. Dense vector search finds
 * chunks that mean the same thing as the question; Postgres full-text search
 * finds chunks that contain the question's actual words. Support questions need
 * both, and lean harder on the second than a general knowledge base would: the
 * terms that identify a problem - an error code, a config key, a class name, a
 * version - are tokens, not concepts, and an embedding is exactly the wrong
 * tool for finding a token. Fusion and the per-document cap live in
 * {@link Reranker}; the SQL that produces the candidates lives in the
 * repository; this class decides only whether hybrid runs and how wide.
 *
 * <p>Dense-only remains available behind {@code app.rag.hybrid-enabled} and is
 * the fallback if the lexical query fails for any reason - a degraded search is
 * a better outcome than no search, and the failure is logged rather than
 * silently absorbed.
 *
 * <p>The whole method - embedding generation AND the Postgres queries - sits
 * behind the {@code vectorSearch} circuit breaker. A finer split would need the
 * repository call in its own Spring bean (self-invocation bypasses the AOP
 * proxy Resilience4j's annotation relies on) purely to get separate breaker
 * identities - not worth the extra class for this application's size. The
 * practical effect: an embedding-provider outage and a Postgres outage both
 * count against the same breaker and both degrade the same way (see
 * {@link #searchFallback}) - a reasonable simplification, not a silent gap.
 */
@Service
public class PgVectorSemanticSearchService implements SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSemanticSearchService.class);

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository documentChunkRepository;
    private final AiPipelineMetrics aiPipelineMetrics;
    private final KnowledgeBaseAccessPolicy knowledgeBaseAccessPolicy;
    private final Reranker reranker;
    private final RagProperties ragProperties;

    public PgVectorSemanticSearchService(EmbeddingService embeddingService,
                                          DocumentChunkRepository documentChunkRepository,
                                          AiPipelineMetrics aiPipelineMetrics,
                                          KnowledgeBaseAccessPolicy knowledgeBaseAccessPolicy,
                                          Reranker reranker,
                                          RagProperties ragProperties) {
        this.embeddingService = embeddingService;
        this.documentChunkRepository = documentChunkRepository;
        this.aiPipelineMetrics = aiPipelineMetrics;
        this.knowledgeBaseAccessPolicy = knowledgeBaseAccessPolicy;
        this.reranker = reranker;
        this.ragProperties = ragProperties;
    }

    @Override
    public List<SemanticSearchResult> search(String query, int limit) {
        return search(query, limit, Map.of());
    }

    @Override
    @CircuitBreaker(name = "vectorSearch", fallbackMethod = "searchFallback")
    public List<SemanticSearchResult> search(String query, int limit, Map<String, String> metadataFilter) {
        // Embedding generation is deliberately NOT included in the timed
        // block below - Spring AI's own observability already measures
        // embeddingService.embed(...) (gen_ai.client.operation.duration).
        // Timing only the repository call isolates the Postgres work itself.
        float[] queryEmbedding = embeddingService.embed(query);
        // Scoped to the embedding model currently in use: vectors from a
        // different model are not comparable, so including them would return
        // arbitrary results rather than merely worse ones.
        String embeddingModel = embeddingService.modelName();
        // The caller's requested filter and the application's policy about
        // this caller are combined HERE, at the single point every retrieval
        // path already funnels through, so /api/qa, /api/documents/search, the
        // agent's knowledge-base step and the MCP-exposed searchKnowledgeBase
        // tool all inherit the audience restriction without each remembering to
        // apply it.
        RetrievalFilter filter = new RetrievalFilter(
                metadataFilter, knowledgeBaseAccessPolicy.exclusionsForCurrentCaller());

        if (ragProperties.hybridEnabled()) {
            try {
                return hybridSearch(query, queryEmbedding, limit, embeddingModel, filter, metadataFilter);
            } catch (RuntimeException e) {
                // Dense-only is a genuinely usable search, so a lexical-side
                // failure degrades rather than fails. Logged at WARN because a
                // permanent degradation to dense-only would otherwise be
                // invisible: answers would just get quietly worse.
                log.warn("Hybrid retrieval failed - falling back to dense-only search for this query", e);
            }
        }
        return denseSearch(queryEmbedding, limit, embeddingModel, filter, metadataFilter);
    }

    private List<SemanticSearchResult> hybridSearch(String query, float[] queryEmbedding, int limit,
                                                      String embeddingModel, RetrievalFilter filter,
                                                      Map<String, String> metadataFilter) {
        // Retrieve wide, rank narrow: each search contributes several times the
        // requested top-K, because the result worth having is often the one
        // that placed mid-table in one search and near the top of the other,
        // and a re-ranker can only reorder what it was handed.
        int candidateLimit = ragProperties.candidateLimit();
        List<HybridChunkMatch> candidates = aiPipelineMetrics.timeVectorSearch(
                () -> documentChunkRepository.findHybridCandidates(
                        queryEmbedding, query, candidateLimit, embeddingModel, filter));
        aiPipelineMetrics.recordRetrievedDocuments(candidates.size());
        aiPipelineMetrics.recordLexicalOnlyCandidates(
                (int) candidates.stream().filter(c -> c.matchedLexically() && !c.matchedDensely()).count());

        if (candidates.isEmpty()) {
            log.debug("Hybrid search returned no candidates for embedding model {} (filter={})",
                    embeddingModel, metadataFilter);
            return List.of();
        }
        return reranker.rerank(candidates, limit);
    }

    private List<SemanticSearchResult> denseSearch(float[] queryEmbedding, int limit, String embeddingModel,
                                                    RetrievalFilter filter, Map<String, String> metadataFilter) {
        List<SimilarChunk> nearest = aiPipelineMetrics.timeVectorSearch(
                () -> documentChunkRepository.findNearest(queryEmbedding, limit, embeddingModel, filter));
        aiPipelineMetrics.recordRetrievedDocuments(nearest.size());
        if (nearest.isEmpty()) {
            log.debug("Vector search returned no chunks for embedding model {} (filter={})",
                    embeddingModel, metadataFilter);
        }
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
    private List<SemanticSearchResult> searchFallback(String query, int limit, Map<String, String> metadataFilter,
                                                       Throwable cause) {
        log.error("Vector search circuit breaker fallback triggered - retrieval is degraded to zero results", cause);
        return List.of();
    }
}
