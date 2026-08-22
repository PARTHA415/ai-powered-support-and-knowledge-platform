package com.example.aiplatform.security;

import com.example.aiplatform.config.RateLimitProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Phase 16: a per-caller request budget. Keyed per-caller, not applied as
 * one global budget, deliberately: a single noisy/compromised account or
 * script should not be able to starve every other caller's share of a
 * shared, billed, per-call LLM budget - a global limiter would let exactly
 * that happen.
 *
 * Uses Resilience4j's {@link RateLimiterRegistry} - the same library this
 * phase adds for circuit breakers - rather than a hand-rolled counter,
 * specifically for its built-in per-key isolation: {@code registry.rateLimiter(key, config)}
 * creates or reuses one independent limiter per key, so this filter doesn't
 * need to manage its own map of buckets or worry about concurrent access to
 * one.
 *
 * Deliberately given NO explicit {@code @Order} - like {@link com.example.aiplatform.ai.guardrails.GuardrailRequestFilter},
 * that defaults it to run AFTER Spring Security's own filter chain (which
 * runs at a much higher precedence), and that's required here, not just
 * incidental: keying by {@link Authentication#getName()} only works once
 * Spring Security has actually populated the {@link SecurityContextHolder}
 * for this request. Running any earlier (e.g. at
 * {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE}, where
 * {@link com.example.aiplatform.observability.CorrelationIdFilter} runs
 * deliberately, so a correlation ID exists even for auth failures) would
 * see no authentication yet and silently fall back to IP-based keying for
 * every request, defeating the per-caller design.
 *
 * Builds its own {@link RateLimiterRegistry} rather than injecting the
 * auto-configured one Resilience4j's starter provides: limiters here are
 * created dynamically, keyed per-caller, and never reference any of the
 * named instances application.yml configures (there are none for rate
 * limiting - only circuit breakers are configured there), so there's
 * nothing to share with the auto-configured bean. Owning its registry
 * directly also keeps this class self-sufficient in a narrower Spring
 * context that doesn't run Resilience4j's full autoconfiguration - e.g. a
 * {@code @WebMvcTest} slice, which auto-includes this class (a
 * {@code Filter} bean) the same way it does {@code GuardrailRequestFilter}.
 * {@code @EnableConfigurationProperties} covers {@link RateLimitProperties}
 * for the identical reason - see {@link com.example.aiplatform.ai.guardrails.ToolExecutionGuard}
 * for the original version of this pattern (Phase 12).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.ofDefaults();
    private final RateLimiterConfig rateLimiterConfig;

    public RateLimitFilter(RateLimitProperties rateLimitProperties) {
        this.rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(rateLimitProperties.requestsPerWindow())
                .limitRefreshPeriod(Duration.ofSeconds(rateLimitProperties.windowSeconds()))
                // Don't block the request thread waiting for the next
                // window - either a permit is available now, or the caller
                // gets a 429 immediately.
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = callerKey(request);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("caller:" + key, rateLimiterConfig);

        if (!rateLimiter.acquirePermission()) {
            log.warn("Rate limit exceeded for caller {}", key);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                            + "\"message\":\"Rate limit exceeded - please slow down and try again shortly.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String callerKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return authentication.getName();
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }
}
