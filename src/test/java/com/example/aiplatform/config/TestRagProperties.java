package com.example.aiplatform.config;

/**
 * Builds {@link RagProperties} for tests without every call site restating
 * eleven values, ten of which it does not care about.
 *
 * <p>Worth its own class rather than a constant per test: {@code RagProperties}
 * grew from five fields to eleven when hybrid retrieval arrived, and without a
 * fixture that change meant editing every test that mentions it, most of which
 * are testing something else entirely. The next field added should touch this
 * file and nothing else.
 */
public final class TestRagProperties {

    private TestRagProperties() {
    }

    /** Production-shaped defaults, hybrid retrieval on. */
    public static RagProperties defaults() {
        return of(220, 40, 32, 5, 0.5);
    }

    public static RagProperties topK(int topK) {
        return of(220, 40, 32, topK, 0.5);
    }

    public static RagProperties chunking(int chunkTokens, int overlapTokens, int embeddingBatchSize) {
        return of(chunkTokens, overlapTokens, embeddingBatchSize, 5, 0.5);
    }

    public static RagProperties of(int chunkTokens, int overlapTokens, int embeddingBatchSize,
                                    int topK, double similarityThreshold) {
        return new RagProperties(chunkTokens, overlapTokens, embeddingBatchSize, topK, similarityThreshold,
                true, 4, 60, 1.0, 1.0, 2);
    }

    /** Dense-only, for tests that assert on the pre-hybrid retrieval path. */
    public static RagProperties denseOnly(int topK, double similarityThreshold) {
        return new RagProperties(220, 40, 32, topK, similarityThreshold, false, 4, 60, 1.0, 1.0, 2);
    }
}
