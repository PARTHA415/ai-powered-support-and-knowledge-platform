package com.example.aiplatform.model;

import java.util.Map;

/**
 * What a retrieval query is allowed to match, beyond nearest-neighbour
 * distance.
 *
 * <p>Two maps rather than one because they come from different places and
 * carry different authority. {@code mustContain} is the caller's own request -
 * narrow this search to Kafka documents. {@code mustNotContain} is the
 * application's policy about that caller - a customer must not retrieve
 * internal runbooks - and is never supplied by the caller, only by
 * {@link com.example.aiplatform.ai.rag.KnowledgeBaseAccessPolicy}.
 *
 * <p>Keeping them separate is what stops a caller-supplied filter from
 * cancelling a policy exclusion: they are combined in SQL as
 * {@code (matches mustContain) AND NOT (matches mustNotContain)}, so no
 * request body can widen what it is allowed to see.
 */
public record RetrievalFilter(Map<String, String> mustContain, Map<String, String> mustNotContain) {

    public RetrievalFilter {
        mustContain = mustContain == null ? Map.of() : Map.copyOf(mustContain);
        mustNotContain = mustNotContain == null ? Map.of() : Map.copyOf(mustNotContain);
    }

    public static RetrievalFilter none() {
        return new RetrievalFilter(Map.of(), Map.of());
    }

    public boolean hasInclusions() {
        return !mustContain.isEmpty();
    }

    public boolean hasExclusions() {
        return !mustNotContain.isEmpty();
    }
}
