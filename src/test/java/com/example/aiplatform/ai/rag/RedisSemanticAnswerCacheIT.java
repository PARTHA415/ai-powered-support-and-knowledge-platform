package com.example.aiplatform.ai.rag;

import com.example.aiplatform.config.SemanticCacheProperties;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.model.Role;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.security.TestPrincipals;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The semantic cache against real Redis - and specifically the property that
 * is an AUTHORIZATION concern rather than a caching one.
 *
 * <p>A cache hit skips retrieval entirely, so the per-document access control
 * enforced at retrieval time cannot help: if a staff answer synthesised from an
 * internal runbook were served from cache to a customer, no document would ever
 * be filtered because none would be fetched. Partitioning by the caller's
 * retrieval scope is what keeps the cache from quietly becoming the most
 * permissive path in the application, and that is what these tests pin.
 */
@Testcontainers
class RedisSemanticAnswerCacheIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisSemanticAnswerCache cache;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cache = newCache(0.95, 200, true);
    }

    private RedisSemanticAnswerCache newCache(double threshold, int maxEntries, boolean enabled) {
        return new RedisSemanticAnswerCache(redisTemplate, new ObjectMapper(),
                new SemanticCacheProperties(enabled, threshold, maxEntries, Duration.ofMinutes(10)),
                new KnowledgeBaseAccessPolicy(),
                new AiPipelineMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        TestPrincipals.clear();
    }

    @Test
    void aStoredAnswerIsReturnedForANearIdenticalQuestionEmbedding() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.SUPPORT_AGENT));

        cache.store("How do I fix consumer lag?", vector(1.0f, 0.0f, 0.0f),
                answer("Restart the consumer group. [1]"));
        Optional<AskResponse> hit = cache.lookup("kafka consumer lag - how to fix?", vector(0.999f, 0.01f, 0.0f));

        assertThat(hit).isPresent();
        assertThat(hit.get().answer()).isEqualTo("Restart the consumer group. [1]");
    }

    @Test
    void aDifferentQuestionBelowTheSimilarityThresholdIsAMiss() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.SUPPORT_AGENT));

        cache.store("How do I fix consumer lag?", vector(1.0f, 0.0f, 0.0f), answer("Restart it."));

        assertThat(cache.lookup("How do I rotate my API key?", vector(0.0f, 1.0f, 0.0f))).isEmpty();
    }

    /**
     * The one that matters. A staff answer - which may have been synthesised
     * from an internal runbook - must not be served to a customer, and no
     * retrieval-time filter can prevent that, because a cache hit performs no
     * retrieval.
     */
    @Test
    void anAnswerCachedForStaffIsNotServedToACustomer() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.SUPPORT_AGENT));
        cache.store("How do I rotate the signing key?", vector(1.0f, 0.0f, 0.0f),
                answer("Run the rotation script documented in the internal runbook. [1]"));

        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1001"));

        assertThat(cache.lookup("How do I rotate the signing key?", vector(1.0f, 0.0f, 0.0f))).isEmpty();
    }

    @Test
    void twoCallersWithTheSameRetrievalScopeShareCachedAnswers() {
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1001"));
        cache.store("How do I reset my password?", vector(1.0f, 0.0f, 0.0f), answer("Go to Settings. [1]"));

        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-2002"));

        assertThat(cache.lookup("How do I reset my password?", vector(1.0f, 0.0f, 0.0f))).isPresent();
    }

    /**
     * An absent principal gets the most restrictive scope - the same
     * fail-closed default {@link KnowledgeBaseAccessPolicy} applies to
     * retrieval - so a staff-scoped entry cannot be reached simply by having no
     * principal at all.
     */
    @Test
    void anAbsentPrincipalDoesNotShareTheStaffPartition() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.ADMIN));
        cache.store("internal question", vector(1.0f, 0.0f, 0.0f), answer("internal answer"));

        TestPrincipals.clear();

        assertThat(cache.lookup("internal question", vector(1.0f, 0.0f, 0.0f))).isEmpty();
    }

    @Test
    void theCachedListIsTrimmedToTheConfiguredBound() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.ADMIN));
        RedisSemanticAnswerCache small = newCache(0.95, 2, true);

        small.store("first", vector(1.0f, 0.0f, 0.0f), answer("first answer"));
        small.store("second", vector(0.0f, 1.0f, 0.0f), answer("second answer"));
        small.store("third", vector(0.0f, 0.0f, 1.0f), answer("third answer"));

        // The oldest entry has been evicted; the two newest are still hits.
        assertThat(small.lookup("first", vector(1.0f, 0.0f, 0.0f))).isEmpty();
        assertThat(small.lookup("third", vector(0.0f, 0.0f, 1.0f))).isPresent();
    }

    @Test
    void aDisabledCacheNeverStoresAndNeverHits() {
        TestPrincipals.authenticateAs(TestPrincipals.staff(Role.ADMIN));
        RedisSemanticAnswerCache disabled = newCache(0.95, 200, false);

        disabled.store("question", vector(1.0f, 0.0f, 0.0f), answer("answer"));

        assertThat(disabled.lookup("question", vector(1.0f, 0.0f, 0.0f))).isEmpty();
    }

    private static AskResponse answer(String text) {
        return new AskResponse(text, "gpt-4o", List.of(new SemanticSearchResult("Doc", "content", 0.9)));
    }

    private static float[] vector(float... values) {
        return values;
    }
}
