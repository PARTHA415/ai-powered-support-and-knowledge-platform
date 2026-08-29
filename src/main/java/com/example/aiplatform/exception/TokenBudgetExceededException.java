package com.example.aiplatform.exception;

/**
 * The caller has spent their token budget for the current window.
 *
 * <p>Distinct from {@link ToolExecutionLimitExceededException} and from the
 * request-count rate limiter, which both bound how MANY things a caller may do.
 * This bounds how EXPENSIVE those things were allowed to be, and the difference
 * is not academic: thirty requests is thirty requests whether each one embeds a
 * one-line question or stuffs a 6,000-token document into the context, and only
 * one of those two shapes can empty a budget in an afternoon.
 *
 * <p>Mapped to 429 like the other budget exceptions - the caller may retry once
 * the window rolls over - and listed in the circuit breaker's
 * {@code ignore-exceptions} for the same reason as the rest: a caller hitting
 * their own spending limit says nothing about the provider's health, and must
 * never be able to open a breaker that serves everyone.
 */
public class TokenBudgetExceededException extends RuntimeException {

    public TokenBudgetExceededException(String message) {
        super(message);
    }
}
