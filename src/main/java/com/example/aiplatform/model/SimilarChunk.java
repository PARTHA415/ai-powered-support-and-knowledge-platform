package com.example.aiplatform.model;

/**
 * Raw nearest-neighbor result straight from the repository. distance is
 * pgvector's cosine distance (0 = identical, larger = less similar) - lower
 * is better. See SemanticSearchResult for the API-facing, similarity-based
 * shape (higher is better).
 */
public record SimilarChunk(
        Long chunkId,
        Long documentId,
        String documentTitle,
        String content,
        double distance
) {
}
