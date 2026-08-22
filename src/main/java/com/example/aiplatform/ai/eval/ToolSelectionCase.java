package com.example.aiplatform.ai.eval;

import java.util.Map;

/**
 * Evaluates "given this tool selection decision, does the application
 * handle it correctly" - NOT "would the model actually choose this tool for
 * this question," which would require a live LLM call and is therefore not
 * something this fixed, repeatable dataset attempts (see the Phase 15
 * docs). {@code toolName}/{@code arguments} stand in for what a real model
 * would have decided to call; the case then checks whether {@link SupportTools}
 * - the same, real, production tool surface Phase 8 built - produces the
 * expected class of outcome: the right data for a legitimate call
 * ({@code SUCCESS}), a denial for a cross-customer call ({@code DENIED} -
 * Phase 11's authorization), rejection of a malformed argument
 * ({@code INVALID_ARGUMENT} - tool argument correctness), or a clean
 * not-found for a well-formed but nonexistent ID ({@code NOT_FOUND}).
 *
 * {@code callerCustomerId} (nullable - null means "no caller identity
 * needed", e.g. {@code checkInventory}) is read only by test code, which
 * uses it to set up a fake {@code SecurityContext} via {@code TestPrincipals}
 * before dispatching - see {@link ToolSelectionScorer}'s Javadoc for why
 * that setup deliberately does not happen in main code.
 */
public record ToolSelectionCase(
        String id,
        String toolName,
        Map<String, String> arguments,
        String callerCustomerId,
        ExpectedOutcome expectedOutcome,
        String expectedResultContains
) {
    public enum ExpectedOutcome {
        SUCCESS, DENIED, INVALID_ARGUMENT, NOT_FOUND
    }
}
