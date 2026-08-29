package com.example.aiplatform.ai.rag;

import com.example.aiplatform.model.HybridChunkMatch;
import com.example.aiplatform.model.SemanticSearchResult;

import java.util.List;

/**
 * Decides the final order of retrieved chunks, and how many survive.
 *
 * <p>Separated from the repository on purpose. Retrieval and ranking are
 * different concerns that change for different reasons: the repository changes
 * when the storage does, the ranking changes when answer quality needs it, and
 * the ranking is the half worth iterating on. Keeping it in Java rather than in
 * the SQL string also means it can be tested without a database, which is what
 * makes it safe to iterate on at all.
 *
 * <p>An interface rather than a class because this is the seam where a real
 * cross-encoder re-ranker would go. Reciprocal rank fusion is a strong,
 * free, deterministic baseline; a cross-encoder that reads the query and each
 * chunk together scores better and costs a model call per candidate. Swapping
 * one for the other should be a bean change, not a rewrite of the retrieval
 * path.
 */
public interface Reranker {

    /**
     * @param candidates the union of both searches' results, each carrying its
     *                   rank in whichever searches found it
     * @param limit      how many results the caller asked for
     */
    List<SemanticSearchResult> rerank(List<HybridChunkMatch> candidates, int limit);
}
