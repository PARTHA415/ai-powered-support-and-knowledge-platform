package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.exception.ToolExecutionLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-request counter capping how many {@code @Tool} calls (across ALL tools,
 * summed) may execute while handling one HTTP request. Independent of, and a
 * lower-level defense than, the agent workflow's own maxIterations bound
 * (Phase 9): that bound caps capability *steps* (one knowledge-base search,
 * one tool-enabled LLM round); this bound caps individual tool *invocations*
 * within any of those rounds, and applies equally to the direct tool-calling
 * endpoints (/api/support/assist, /api/chat) that have no iteration concept
 * of their own at all.
 *
 * Implemented as a {@link ThreadLocal} for the same reason Spring Security's
 * SecurityContextHolder is: Spring MVC handles one request per thread
 * synchronously, so a thread-local counter is naturally request-scoped as
 * long as something resets it at the start of each request and clears it at
 * the end - see {@link GuardrailRequestFilter}, which does exactly that,
 * mirroring how Spring Security's own filter manages the SecurityContext.
 *
 * {@code @EnableConfigurationProperties} here (rather than relying solely on
 * the app-wide {@code @ConfigurationPropertiesScan}) keeps this class
 * self-sufficient when it's pulled into a narrower Spring context that
 * doesn't scan the whole application - e.g. a {@code @WebMvcTest} slice,
 * which auto-includes {@link GuardrailRequestFilter} (a {@code Filter}
 * bean) and therefore needs this class's dependencies resolvable too.
 */
@Component
@EnableConfigurationProperties(GuardrailProperties.class)
public class ToolExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionGuard.class);

    private final GuardrailProperties guardrailProperties;
    private final ThreadLocal<AtomicInteger> callCount = ThreadLocal.withInitial(AtomicInteger::new);

    public ToolExecutionGuard(GuardrailProperties guardrailProperties) {
        this.guardrailProperties = guardrailProperties;
    }

    /**
     * Records one tool invocation and throws once the configured limit is
     * exceeded. Called as the first line of every {@code @Tool} method in
     * {@link com.example.aiplatform.ai.tools.SupportTools} - deterministic
     * Java arithmetic the model has no way to influence or bypass.
     */
    public void recordInvocation(String toolName) {
        int count = callCount.get().incrementAndGet();
        if (count > guardrailProperties.maxToolCallsPerRequest()) {
            log.warn("Tool call limit ({}) exceeded on this request; blocking call to {}",
                    guardrailProperties.maxToolCallsPerRequest(), toolName);
            throw new ToolExecutionLimitExceededException(
                    "Tool call limit exceeded (" + guardrailProperties.maxToolCallsPerRequest()
                            + " calls) for this request; refusing to call " + toolName + " again.");
        }
    }

    /** Resets the counter to zero - called at the start of each request. */
    public void reset() {
        callCount.get().set(0);
    }

    /** Removes the thread-local entirely - called at the end of each request. */
    public void clear() {
        callCount.remove();
    }
}
