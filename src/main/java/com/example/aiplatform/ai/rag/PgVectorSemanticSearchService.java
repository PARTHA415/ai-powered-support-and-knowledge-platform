package com.example.aiplatform.ai.rag;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.model.SimilarChunk;
import com.example.aiplatform.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PgVectorSemanticSearchService implements SemanticSearchService {

    private final EmbeddingService embeddingService;
    private final DocumentChunkRepository documentChunkRepository;

    public PgVectorSemanticSearchService(EmbeddingService embeddingService,
                                          DocumentChunkRepository documentChunkRepository) {
        this.embeddingService = embeddingService;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Override
    public List<SemanticSearchResult> search(String query, int limit) {
        float[] queryEmbedding = embeddingService.embed(query);
        List<SimilarChunk> nearest = documentChunkRepository.findNearest(queryEmbedding, limit);
        return nearest.stream()
                .map(chunk -> new SemanticSearchResult(chunk.documentTitle(), chunk.content(), 1 - chunk.distance()))
                .toList();
    }
}
