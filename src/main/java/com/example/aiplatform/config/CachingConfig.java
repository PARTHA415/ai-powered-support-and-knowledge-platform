package com.example.aiplatform.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Phase 16: enables {@code @Cacheable} (Spring Boot does not turn on the
 * caching AOP proxy just because a {@code CacheManager} bean exists - this
 * is required explicitly). {@code order = HIGHEST_PRECEDENCE} is not
 * cosmetic: {@link com.example.aiplatform.ai.embedding.SpringAiEmbeddingService#embed}
 * carries both {@code @Cacheable} and {@code @CircuitBreaker}, and the cache
 * check MUST be the outermost aspect - a cache hit has to be served
 * immediately, without ever reaching the circuit breaker's permission
 * check. Without this explicit ordering, the two aspects' relative order
 * would depend on Resilience4j's own default (undocumented for this
 * purpose), risking a cached, perfectly servable result being rejected by
 * an open breaker that a real call would never have reached anyway.
 */
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CachingConfig {
}
