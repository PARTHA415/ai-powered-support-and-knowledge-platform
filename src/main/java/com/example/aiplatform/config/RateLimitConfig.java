package com.example.aiplatform.config;

import com.example.aiplatform.security.AuthenticationRateLimitFilter;
import com.example.aiplatform.security.RateLimitFilter;
import com.example.aiplatform.security.RedisFixedWindowRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers the two rate-limiting filters explicitly rather than letting them
 * be picked up as {@code @Component} beans.
 *
 * <p>That is a deliberate change of registration style, not bureaucracy. While
 * the limiter was an in-process counter, the filters were self-sufficient and
 * could be auto-detected anywhere - including inside a {@code @WebMvcTest}
 * slice, which scans for {@code Filter} beans and instantiates them. Now that
 * they need Redis, auto-detection broke every controller slice test in the
 * codebase: the slice has no {@link StringRedisTemplate}, so constructing the
 * filters failed and the whole application context failed with it.
 *
 * <p>Declaring them as {@code @Bean}s here means a slice test never tries to
 * build them (it does not include this configuration class), while the full
 * application still gets both. The {@code @ConditionalOnBean} guard makes the
 * dependency explicit rather than implicit: no Redis, no rate limiting, and a
 * context that starts instead of one that fails with a bean-wiring error.
 */
@Configuration
@ConditionalOnBean(StringRedisTemplate.class)
public class RateLimitConfig {

    @Bean
    public RateLimitFilter rateLimitFilter(RedisFixedWindowRateLimiter rateLimiter,
                                            RateLimitProperties rateLimitProperties) {
        return new RateLimitFilter(rateLimiter, rateLimitProperties);
    }

    @Bean
    public AuthenticationRateLimitFilter authenticationRateLimitFilter(RedisFixedWindowRateLimiter rateLimiter,
                                                                        RateLimitProperties rateLimitProperties) {
        return new AuthenticationRateLimitFilter(rateLimiter, rateLimitProperties);
    }
}
