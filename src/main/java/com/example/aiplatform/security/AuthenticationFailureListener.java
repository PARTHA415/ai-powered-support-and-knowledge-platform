package com.example.aiplatform.security;

import com.example.aiplatform.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Counts failed authentications per source IP, feeding
 * {@link AuthenticationRateLimitFilter}.
 *
 * <p>Uses Spring Security's own authentication events rather than trying to
 * detect failures from the outside. That matters for correctness: the filter
 * cannot tell a 401 caused by bad credentials from any other 401, and it runs
 * before authentication has even been attempted. The event is emitted by the
 * authentication manager itself, so it fires exactly when credentials were
 * actually rejected - no guessing from status codes.
 *
 * <p>A successful login clears the counter for that IP, so a legitimate user
 * who mistypes a password a few times and then gets it right is not left
 * sitting out the rest of the window.
 */
@Component
public class AuthenticationFailureListener {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFailureListener.class);
    private static final String KEY_PREFIX = "ratelimit:authfail:";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public AuthenticationFailureListener(RedisFixedWindowRateLimiter rateLimiter,
                                          RateLimitProperties rateLimitProperties) {
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    static String failureKey(String clientIp) {
        return KEY_PREFIX + clientIp;
    }

    /**
     * Resolves the client IP from the servlet request. Deliberately does NOT
     * trust {@code X-Forwarded-For}: behind no proxy, that header is
     * attacker-controlled, and honouring it would let one source mint a fresh
     * identity per request and bypass this entirely. A deployment that does sit
     * behind a trusted proxy should configure Spring Boot's
     * {@code server.forward-headers-strategy} so {@code getRemoteAddr()} itself
     * returns the real client - the correct place to make that decision, since
     * only the deployment knows whether a proxy is actually in front.
     */
    static String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String clientIp = ipFrom(event.getAuthentication().getDetails());
        rateLimiter.tryAcquire(failureKey(clientIp),
                rateLimitProperties.maxFailedLoginsPerWindow(),
                rateLimitProperties.authFailureWindowSeconds());
        log.warn("Failed authentication attempt from {}", clientIp);
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String clientIp = ipFrom(event.getAuthentication().getDetails());
        if (!"unknown".equals(clientIp)) {
            rateLimiter.reset(failureKey(clientIp));
        }
    }

    private static String ipFrom(Object details) {
        if (details instanceof WebAuthenticationDetails webDetails && webDetails.getRemoteAddress() != null) {
            return webDetails.getRemoteAddress();
        }
        return "unknown";
    }
}
