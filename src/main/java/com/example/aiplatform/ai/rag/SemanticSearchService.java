package com.example.aiplatform.ai.rag;

import com.example.aiplatform.model.SemanticSearchResult;

import java.util.List;

/**
 * The retrieval half of RAG: embed a query and find the nearest stored
 * chunks. Deliberately stops there - no prompt construction, no LLM call.
 * Phase 6 builds the "generation" half on top of this.
 */
public interface SemanticSearchService {

    List<SemanticSearchResult> search(String query, int limit);
}
