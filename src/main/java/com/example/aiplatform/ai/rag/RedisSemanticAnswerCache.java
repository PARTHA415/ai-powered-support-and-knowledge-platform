package com.example.aiplatform.ai.rag;

import com.example.aiplatform.config.SemanticCacheProperties;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Redis-backed {@link SemanticAnswerCache}.
 *
 * <h2>Why Redis and not a local map</h2>
 *
 * The same reason the embedding cache and the rate limiter moved there: a
 * per-instance cache divides its own hit rate by the replica count, so the
 * saving it exists to produce shrinks exactly as the deployment grows. An
 * answer any instance has computed should be a hit for all of them.
 *
 * <h2>Why the lookup is a linear scan, and when that stops being acceptable</h2>
 *
 * Lookup pulls the scope's recent entries and compares the query embedding
 * against each. That is O(entries x dimensions) per call - about 300k float
 * multiplications at the default bound of 200 entries, which is well under a
 * millisecond and is being weighed against a retrieval round trip plus a
 * multi-second LLM call. It is the right trade at this size and the wrong one at
 * ten thousand entries.
 *
 * <p>The honest alternative is not a bigger scan: it is a second vector index.
 * pgvector is already in this stack and could hold cached questions in a table
 * of its own, turning the scan into an indexed nearest-neighbour lookup. That is
 * the change to make when the bound here starts costing hit rate - and the bound
 * is where it is precisely so that the day it starts costing hit rate is
 * visible, rather than the scan quietly becoming the slow part.
 *
 * <h2>Storage format</h2>
 *
 * Embeddings are stored as base64 of little-endian float32 rather than as a JSON
 * array of numbers: 6KB per entry instead of about 15KB, which at 200 entries
 * per scope is the difference between roughly 1MB and 3MB of Redis per scope.
 * The list is trimmed and given a TTL on write, so a scope that stops being used
 * disappears on its own.
 */
@Component
public class RedisSemanticAnswerCache implements SemanticAnswerCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSemanticAnswerCache.class);

    private static final String KEY_PREFIX = "semantic-answer-cache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SemanticCacheProperties properties;
    private final KnowledgeBaseAccessPolicy knowledgeBaseAccessPolicy;
    private final AiPipelineMetrics aiPipelineMetrics;

    public RedisSemanticAnswerCache(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     SemanticCacheProperties properties,
                                     KnowledgeBaseAccessPolicy knowledgeBaseAccessPolicy,
                                     AiPipelineMetrics aiPipelineMetrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.knowledgeBaseAccessPolicy = knowledgeBaseAccessPolicy;
        this.aiPipelineMetrics = aiPipelineMetrics;
    }

    /** The stored shape. A record so the JSON contract is visible in one place. */
    record CachedAnswer(String question, String embedding, AskResponse response) {
    }

    @Override
    public Optional<AskResponse> lookup(String question, float[] questionEmbedding) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        List<String> entries;
        try {
            entries = redisTemplate.opsForList().range(scopedKey(), 0, properties.maxEntriesPerScope() - 1);
        } catch (RuntimeException e) {
            // A cache is an optimisation. An unreachable one means the request
            // is slower and more expensive, never that it fails.
            log.warn("Semantic answer cache lookup failed - answering without it", e);
            return Optional.empty();
        }
        if (entries == null || entries.isEmpty()) {
            aiPipelineMetrics.recordSemanticCacheOutcome("miss");
            return Optional.empty();
        }

        CachedAnswer best = null;
        double bestSimilarity = properties.similarityThreshold();
        for (String entry : entries) {
            CachedAnswer cached = deserialize(entry);
            if (cached == null) {
                continue;
            }
            double similarity = cosineSimilarity(questionEmbedding, decodeEmbedding(cached.embedding()));
            if (similarity >= bestSimilarity) {
                bestSimilarity = similarity;
                best = cached;
            }
        }

        if (best == null) {
            aiPipelineMetrics.recordSemanticCacheOutcome("miss");
            return Optional.empty();
        }
        aiPipelineMetrics.recordSemanticCacheOutcome("hit");
        log.debug("Semantic cache hit at similarity {}: '{}' answered with the response to '{}'",
                bestSimilarity, question, best.question());
        return Optional.of(best.response());
    }

    @Override
    public void store(String question, float[] questionEmbedding, AskResponse response) {
        if (!properties.enabled() || response == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(
                    new CachedAnswer(question, encodeEmbedding(questionEmbedding), response));
            String key = scopedKey();
            redisTemplate.opsForList().leftPush(key, payload);
            redisTemplate.opsForList().trim(key, 0, properties.maxEntriesPerScope() - 1);
            redisTemplate.expire(key, properties.ttl());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to store a semantic cache entry - the answer was still returned", e);
        }
    }

    /**
     * The cache partition for the current caller.
     *
     * <p>Derived from the audience exclusions the access policy computes, so
     * two callers share a partition exactly when they may see the same
     * documents. Hashed rather than concatenated so the key stays a fixed
     * length whatever the policy grows into, and so the key itself does not
     * spell out the policy to anyone reading the keyspace.
     */
    private String scopedKey() {
        return KEY_PREFIX + Integer.toHexString(
                knowledgeBaseAccessPolicy.exclusionsForCurrentCaller().hashCode());
    }

    private CachedAnswer deserialize(String entry) {
        try {
            return objectMapper.readValue(entry, CachedAnswer.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // A single unreadable entry - written by an older version of this
            // record, say - must not poison the whole lookup.
            log.debug("Skipping an unreadable semantic cache entry", e);
            return null;
        }
    }

    /**
     * Cosine similarity. The embeddings this application stores are not
     * guaranteed to be unit length, so this normalises rather than assuming a
     * dot product is enough - an assumption that would silently return
     * similarities greater than 1 and make the threshold meaningless.
     */
    static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) {
            return -1;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return -1;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    static String encodeEmbedding(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decodeEmbedding(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] embedding = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }
}
