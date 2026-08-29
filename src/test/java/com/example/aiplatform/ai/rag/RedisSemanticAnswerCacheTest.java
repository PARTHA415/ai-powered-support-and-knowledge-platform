package com.example.aiplatform.ai.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The two pieces of the semantic cache that are pure arithmetic and can be
 * pinned without Redis. The parts that need Redis - scope isolation in
 * particular, which is an authorization property rather than a caching one -
 * are covered in {@code RedisSemanticAnswerCacheIT}.
 */
class RedisSemanticAnswerCacheTest {

    @Test
    void anEmbeddingSurvivesTheBase64RoundTrip() {
        float[] original = {0.1f, -0.25f, 3.5f, 0.0f, 1e-7f};

        float[] roundTripped = RedisSemanticAnswerCache.decodeEmbedding(
                RedisSemanticAnswerCache.encodeEmbedding(original));

        assertThat(roundTripped).containsExactly(original);
    }

    @Test
    void identicalVectorsScoreOne() {
        float[] vector = {0.3f, 0.4f, 0.5f};

        assertThat(RedisSemanticAnswerCache.cosineSimilarity(vector, vector)).isCloseTo(1.0, within(1e-9));
    }

    /**
     * Embeddings are not guaranteed to be unit length, so the implementation
     * normalises rather than taking a bare dot product - which would return
     * values above 1 and make the configured threshold meaningless.
     */
    @Test
    void nonUnitLengthVectorsPointingTheSameWayStillScoreOne() {
        assertThat(RedisSemanticAnswerCache.cosineSimilarity(new float[] {1, 2, 3}, new float[] {10, 20, 30}))
                .isCloseTo(1.0, within(1e-6));
    }

    @Test
    void orthogonalVectorsScoreZero() {
        assertThat(RedisSemanticAnswerCache.cosineSimilarity(new float[] {1, 0}, new float[] {0, 1}))
                .isCloseTo(0.0, within(1e-9));
    }

    /**
     * A mismatched, empty, or zero vector must score below any usable
     * threshold rather than throwing or accidentally matching - a cache lookup
     * is on the request path and must degrade to a miss.
     */
    @Test
    void malformedComparisonsScoreBelowAnyUsableThreshold() {
        assertThat(RedisSemanticAnswerCache.cosineSimilarity(new float[] {1, 2}, new float[] {1, 2, 3}))
                .isEqualTo(-1);
        assertThat(RedisSemanticAnswerCache.cosineSimilarity(new float[] {}, new float[] {})).isEqualTo(-1);
        assertThat(RedisSemanticAnswerCache.cosineSimilarity(new float[] {0, 0}, new float[] {1, 1}))
                .isEqualTo(-1);
        assertThat(RedisSemanticAnswerCache.cosineSimilarity(null, new float[] {1})).isEqualTo(-1);
    }
}
