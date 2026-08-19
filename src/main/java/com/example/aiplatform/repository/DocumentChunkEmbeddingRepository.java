package com.example.aiplatform.repository;

import com.example.aiplatform.model.SimilarChunk;

import java.util.List;

/**
 * The pgvector-specific operations Spring Data JPA can't express as a derived
 * or JPQL query. Implemented with plain SQL - see
 * {@link DocumentChunkEmbeddingRepositoryImpl}.
 */
public interface DocumentChunkEmbeddingRepository {

    void saveEmbedding(Long chunkId, float[] embedding);

    List<SimilarChunk> findNearest(float[] queryEmbedding, int limit);
}
