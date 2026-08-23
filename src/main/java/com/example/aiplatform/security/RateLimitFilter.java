package com.example.aiplatform.security;

import com.example.aiplatform.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A per-caller request budget, keyed per-caller rather than applied as one
 * global budget: a single noisy or compromised account should not be able to
 * starve every other caller's share of a shared, billed, per-call LLM budget.
 *
 * <p>Now backed by {@link RedisFixedWindowRateLimiter} rather than an
 * in-process registry, so the budget is shared across replicas instead of
 * being multiplied by them, and counters expire instead of accumulating
 * forever.
 *
 * <p>Deliberately given NO explicit {@code @Order}, which defaults it to run
 * AFTER Spring Security's filter chain. That is required, not incidental:
 * keying by {@link Authentication#getName()} only works once Spring Security
 * has populated the {@link SecurityContextHolder} for this request.
 *
 * <p>The consequence is that this filter never sees an unauthenticated request
 * - the security chain has already answered 401 by then - which is why the
 * IP-keyed fallback below is effectively unreachable in practice and why
 * brute-force protection needed a separate filter running BEFORE the security
 * chain. See {@link AuthenticationRateLimitFilter}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String KEY_PREFIX = "ratelimit:caller:";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public RateLimitFilter(RedisFixedWindowRateLimiter rateLimiter, RateLimitProperties rateLimitProperties) {
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = KEY_PREFIX + callerKey(request);

        if (!rateLimiter.tryAcquire(key, rateLimitProperties.requestsPerWindow(),
                rateLimitProperties.windowSeconds())) {
            log.warn("Rate limit exceeded for caller {}", callerKey(request));
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
