package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase 16: per-caller request budget enforced by {@code security.RateLimitFilter}.
 * See that class for why the limiter is keyed per-caller rather than
 * applied globally, and application.yml for why the defaults are sized the
 * way they are.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("30") int requestsPerWindow,
        @DefaultValue("60") int windowSeconds
) {
}
