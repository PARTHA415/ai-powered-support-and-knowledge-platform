package com.example.aiplatform.config;

import com.example.aiplatform.security.AuthenticationRateLimitFilter;
import com.example.aiplatform.security.RateLimitFilter;
import com.example.aiplatform.security.RedisFixedWindowRateLimiter;
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
 * application still gets both. That registration style alone solves the slice
 * problem - it needs no additional condition.
 *
 * <p><strong>Do not add {@code @ConditionalOnBean(StringRedisTemplate.class)}
 * here.</strong> It was present once and silently disabled BOTH filters in the
 * running application. {@code @ConditionalOnBean} is only reliable inside
 * auto-configuration classes: a user {@code @Configuration} like this one is
 * parsed before {@code RedisAutoConfiguration} has registered
 * {@link StringRedisTemplate}, so the condition finds nothing and drops the
 * beans. Nothing fails loudly - the app starts, serves traffic, and has no rate
 * limiting at all. It was especially hard to spot because
 * {@code AuthenticationFailureListener} is a plain {@code @Component} and kept
 * incrementing the Redis failure counter, so the protection looked alive from
 * the outside while no request was ever actually blocked.
 *
 * <p>The guard was redundant as well as broken: {@code RedisFixedWindowRateLimiter}
 * is an unconditional {@code @Component} that requires a
 * {@link StringRedisTemplate}, so an application without Redis already fails to
 * start long before it reaches this class.
 */
@Configuration
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
