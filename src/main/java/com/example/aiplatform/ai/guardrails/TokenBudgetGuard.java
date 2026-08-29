package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.ai.llm.LlmCallUsage;
import com.example.aiplatform.config.TokenBudgetProperties;
import com.example.aiplatform.exception.TokenBudgetExceededException;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.security.CurrentUser;
import com.example.aiplatform.security.RedisFixedWindowRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * A spending ceiling per authenticated caller, denominated in tokens.
 *
 * <p><b>Why a second limiter.</b> The request-count rate limiter bounds how
 * often a caller may ask; this bounds what those questions were allowed to
 * cost. They are not substitutes. A caller within the request limit can still
 * burn an arbitrary amount of money by making each request as large as the
 * prompt-size guardrail permits, and no count-based limit can see that
 * happening because it is counting the wrong unit.
 *
 * <p><b>Check before, record after.</b> A call's cost is not knowable until the
 * provider has answered, so the budget is necessarily enforced one call late:
 * the check rejects a caller who is <em>already</em> over, and the recording
 * happens once usage comes back. A caller can therefore overshoot by at most
 * one call. Closing that last gap would mean estimating cost before the call
 * and reserving it, which needs a real tokenizer for the prompt, a guess for
 * the completion, and a compensating refund - considerably more machinery for
 * a bound that is already tight enough to stop the failure mode this exists
 * for (a runaway loop or a compromised credential, not a user who is 3% over).
 *
 * <p><b>Fails open.</b> Like the rate limiter and unlike
 * {@code OwnerScopedChatMemory}, an unreachable Redis means requests proceed
 * unmetered. This is a cost control, not an authorization decision - taking the
 * support platform down because the budget store is unavailable would convert a
 * degraded dependency into an outage, and the bill is recoverable where the
 * outage is not.
 *
 * <p><b>Keys are percent-encoded</b> for the same reason
 * {@code OwnerScopedChatMemory} encodes its principal: a username containing
 * the namespace separator must not be able to land in - or read - another
 * caller's key.
 */
@Component
public class TokenBudgetGuard {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetGuard.class);

    private static final String KEY_PREFIX = "token-budget:";
    private static final String ANONYMOUS = "anonymous";

    private final RedisFixedWindowRateLimiter counter;
    private final TokenBudgetProperties properties;
    private final AiPipelineMetrics aiPipelineMetrics;

    public TokenBudgetGuard(RedisFixedWindowRateLimiter counter,
                             TokenBudgetProperties properties,
                             AiPipelineMetrics aiPipelineMetrics) {
        this.counter = counter;
        this.properties = properties;
        this.aiPipelineMetrics = aiPipelineMetrics;
    }

    /**
     * Rejects the call if this caller has already consumed their window's
     * budget. Called before every LLM request, at the same single choke point
     * as the prompt-size guardrail.
     */
    public void assertWithinBudget() {
        if (!properties.enabled()) {
            return;
        }
        String caller = currentCaller();
        long consumed = counter.currentCount(keyFor(caller));
        if (consumed >= properties.tokensPerWindow()) {
            aiPipelineMetrics.recordSafetyBoundTriggered("token_budget_exceeded");
            log.warn("Caller '{}' has consumed {} tokens against a budget of {} per {}s - rejecting",
                    caller, consumed, properties.tokensPerWindow(), properties.windowSeconds());
            throw new TokenBudgetExceededException(
                    "Token budget exhausted for this window: " + consumed + " of "
                            + properties.tokensPerWindow() + " tokens used. The budget resets within "
                            + properties.windowSeconds() + " seconds.");
        }
    }

    /** Charges one completed call's tokens against the current caller's window. */
    public void record(LlmCallUsage usage) {
        record(usage, currentCaller());
    }

    /**
     * Charges a completed call against a caller captured EARLIER.
     *
     * <p>Needed by the streaming path, and the reason is easy to miss. A
     * streamed response finishes on a reactor thread after the servlet thread
     * has been released, so by the time usage arrives there is no security
     * context to read the caller from - {@link #currentCaller()} would fall back
     * to the shared {@code anonymous} bucket and every streamed call would go
     * unattributed. Capturing the caller while the request thread still holds it
     * is the fix; the alternative, propagating the whole security context into
     * the reactive pipeline, is more machinery for one string.
     */
    public void record(LlmCallUsage usage, String caller) {
        if (!properties.enabled() || usage == null || usage.isEmpty()) {
            return;
        }
        long total = counter.addAndGet(keyFor(caller), usage.totalTokens(), properties.windowSeconds());
        if (total >= properties.tokensPerWindow()) {
            log.info("Caller '{}' has now consumed {} of {} budgeted tokens; the next call will be rejected",
                    caller, total, properties.tokensPerWindow());
        }
    }

    /**
     * The authenticated principal, or a shared {@code anonymous} bucket for the
     * non-HTTP entry points that have no security context (the evaluation
     * harness, tests). Charging those to one shared bucket is deliberate: it
     * keeps unattributed spend visible in one place rather than silently
     * unmetered.
     */
    public String currentCaller() {
        try {
            return CurrentUser.get().getUsername();
        } catch (IllegalStateException e) {
            return ANONYMOUS;
        }
    }

    private static String keyFor(String caller) {
        return KEY_PREFIX + URLEncoder.encode(caller, StandardCharsets.UTF_8);
    }
}
