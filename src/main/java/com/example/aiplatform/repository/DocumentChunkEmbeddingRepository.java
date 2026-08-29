package com.example.aiplatform.repository;

import com.example.aiplatform.model.HybridChunkMatch;
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

    /**
     * Runs the vector search and a Postgres full-text search over the same
     * filtered corpus and returns the union of both candidate sets, each row
     * tagged with where it placed in each search.
     *
     * <p>Fusion is deliberately NOT done here. SQL can express reciprocal-rank
     * fusion perfectly well, but the ranking policy - how the two searches are
     * weighted, how many results one document may occupy - is retrieval
     * strategy, and burying it in a query string makes it unreadable,
     * unreviewable and untestable without a database. The repository's job is
     * to produce evidence; {@link com.example.aiplatform.ai.rag.Reranker}
     * decides what it means.
     *
     * @param candidateLimit how many candidates EACH search may contribute.
     *                       Deliberately larger than the final top-K: the
     *                       principle behind re-ranking is retrieve wide, rank
     *                       narrow, and a re-ranker can only reorder what it was
     *                       given.
     */
    List<HybridChunkMatch> findHybridCandidates(float[] queryEmbedding, String queryText, int candidateLimit,
                                                 String embeddingModel, RetrievalFilter filter);
}
