package com.example.aiplatform.model;

/**
 * similarity is 1 - cosine distance, so higher is better (1.0 = identical) -
 * more intuitive for API consumers than exposing raw distance directly.
 */
public record SemanticSearchResult(
        String documentTitle,
        String content,
        double similarity
) {
}
