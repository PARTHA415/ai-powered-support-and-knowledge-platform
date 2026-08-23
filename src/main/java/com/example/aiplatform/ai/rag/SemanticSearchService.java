package com.example.aiplatform.ai.rag;

import com.example.aiplatform.model.SemanticSearchResult;

import java.util.List;
import java.util.Map;

/**
 * The retrieval half of RAG: embed a query and find the nearest stored
 * chunks. Deliberately stops there - no prompt construction, no LLM call.
 */
public interface SemanticSearchService {

    /** Unfiltered search - equivalent to passing an empty metadata filter. */
    List<SemanticSearchResult> search(String query, int limit);

    /**
     * @param metadataFilter key/value pairs the parent document's metadata must
     *                       contain. Applied in SQL before ranking, not as a
     *                       post-filter, so a caller asking for {@code limit}
     *                       results gets the nearest {@code limit} MATCHING
     *                       chunks rather than however many of an unfiltered
     *                       top-K happened to survive. Null or empty means no
     *                       filtering.
     */
    List<SemanticSearchResult> search(String query, int limit, Map<String, String> metadataFilter);
}
