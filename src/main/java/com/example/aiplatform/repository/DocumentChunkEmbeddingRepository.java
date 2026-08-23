package com.example.aiplatform.repository;

import com.example.aiplatform.model.RetrievalFilter;
import com.example.aiplatform.model.SimilarChunk;

import java.util.List;

/**
 * The pgvector-specific operations Spring Data JPA can't express as a derived
 * or JPQL query. Implemented with plain SQL - see
 * {@link DocumentChunkEmbeddingRepositoryImpl}.
 */
public interface DocumentChunkEmbeddingRepository {

    /**
     * @param embeddingModel which model produced this vector - stored alongside
     *                       it so a mixed index is detectable and re-embeddable
     *                       rather than a silent relevance collapse.
     */
    void saveEmbedding(Long chunkId, float[] embedding, String embeddingModel);

    /**
     * @param embeddingModel only chunks embedded by this model are eligible;
     *                       vectors from a different model are not comparable.
     * @param filter         metadata the parent document must (and must not)
     *                       carry, applied before ranking.
     */
    List<SimilarChunk> findNearest(float[] queryEmbedding, int limit, String embeddingModel, RetrievalFilter filter);
}
