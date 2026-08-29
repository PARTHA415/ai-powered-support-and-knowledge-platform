package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * A per-authenticated-caller ceiling on token consumption, and the window it
 * resets over.
 *
 * <p>The request-count rate limiter
 * ({@link com.example.aiplatform.security.RateLimitFilter}) already bounds how
 * often a caller may ask. It cannot bound what those requests cost, because
 * cost is not a function of request count - one caller pasting large documents
 * into thirty questions spends far more than another asking thirty short ones,
 * and the limiter cannot tell them apart. This is the ceiling denominated in
 * the thing that is actually billed.
 *
 * <p>Disabled by default. A budget that is wrong is worse than no budget: too
 * low and it rejects legitimate support work at the moment someone needs it,
 * and there is no way to pick the number honestly without first watching real
 * traffic through the cost metrics
 * ({@link com.example.aiplatform.observability.CostMeter}). Measure, then set
 * it. Shipping an invented default enabled would be the mistake this comment
 * exists to prevent.
 *
 * @param enabled         whether the budget is enforced at all
 * @param tokensPerWindow total tokens (prompt + completion) one caller may
 *                        consume per window
 * @param windowSeconds   how long the window lasts before the count resets
 */
@ConfigurationProperties(prefix = "app.ai.budget")
public record TokenBudgetProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("200000") long tokensPerWindow,
        @DefaultValue("3600") int windowSeconds
) {
}
