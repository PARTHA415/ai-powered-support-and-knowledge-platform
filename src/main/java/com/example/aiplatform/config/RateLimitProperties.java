package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Budgets for the two rate limiters, which protect against two different
 * things and are therefore tuned independently.
 *
 * <ul>
 *   <li>{@code requestsPerWindow} / {@code windowSeconds} - the per-caller
 *       request budget enforced by {@code security.RateLimitFilter}, sized to
 *       stop a runaway script or compromised credential from hammering billed
 *       LLM endpoints.</li>
 *   <li>{@code maxFailedLoginsPerWindow} / {@code authFailureWindowSeconds} -
 *       per-IP failed-authentication budget enforced by
 *       {@code security.AuthenticationRateLimitFilter}. Counts only FAILURES,
 *       so a busy legitimate client - HTTP Basic re-authenticates on every
 *       request - is never affected by it.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("30") int requestsPerWindow,
        @DefaultValue("60") int windowSeconds,
        @DefaultValue("10") int maxFailedLoginsPerWindow,
        @DefaultValue("300") int authFailureWindowSeconds
) {
}
