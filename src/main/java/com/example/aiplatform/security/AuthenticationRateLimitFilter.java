package com.example.aiplatform.security;

import com.example.aiplatform.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Brute-force protection for HTTP Basic authentication.
 *
 * <p>The application already had a per-caller rate limiter, and it could not do
 * this job. {@link RateLimitFilter} runs after Spring Security's chain, by
 * necessity - it keys on the authenticated principal, which does not exist any
 * earlier. But a request with bad credentials is rejected with 401 by that
 * chain and never reaches it. The result was that every authenticated request
 * was rate limited and every FAILED login was not: no throttling, no lockout,
 * no backoff, with BCrypt's work factor the only thing between an attacker and
 * an unlimited-speed password guessing loop.
 *
 * <p>This filter closes that gap by running at high precedence, ahead of the
 * security chain, and counting only FAILED authentications per source IP -
 * recorded by {@link AuthenticationFailureListener} from Spring Security's own
 * failure events. Counting failures rather than attempts is what keeps this from
 * penalizing legitimate traffic: HTTP Basic re-sends credentials on every
 * request, so a busy, entirely well-behaved client authenticates constantly and
 * should never be affected.
 *
 * <p>It runs just after {@link com.example.aiplatform.observability.CorrelationIdFilter}
 * so a blocked attempt still gets a correlation ID in its log lines.
 *
 * <p><b>Known limitation:</b> IP-keyed throttling is evaded by a distributed
 * source and can over-block callers sharing a NAT. It raises the cost of the
 * cheap, single-source attack, which is the one actually worth stopping here;
 * per-account lockout and proxy-aware client-IP resolution are the next steps.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationRateLimitFilter.class);

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public AuthenticationRateLimitFilter(RedisFixedWindowRateLimiter rateLimiter,
                                          RateLimitProperties rateLimitProperties) {
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientIp = AuthenticationFailureListener.clientIp(request);
        long failures = rateLimiter.currentCount(AuthenticationFailureListener.failureKey(clientIp));

        if (failures >= rateLimitProperties.maxFailedLoginsPerWindow()) {
            log.warn("Blocking request from {} - {} failed authentication attempts in the current window",
                    clientIp, failures);
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                            + "\"message\":\"Too many failed authentication attempts - please wait and try again.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
