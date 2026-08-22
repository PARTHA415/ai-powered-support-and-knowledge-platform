package com.example.aiplatform.ai.rag;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.model.SimilarChunk;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The single place every retrieval path in this application goes through -
 * {@code /api/qa}, the agent workflow's knowledge-base step, and the MCP-
 * exposed {@code searchKnowledgeBase} tool all call {@link #search} - which
 * is why "vector-search latency" and "retrieved document count" are
 * instrumented here rather than in each caller (Phase 14): one measurement
 * point, inherited by every feature that retrieves.
 */
@Service
public class PgVectorSemanticSearchService implements SemanticSearchService {

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
}
