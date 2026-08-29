package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.exception.ToolExecutionLimitExceededException;
import com.example.aiplatform.observability.RequestContext;
import com.example.aiplatform.observability.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-request counter capping how many {@code @Tool} calls (across ALL tools,
 * summed) may execute while handling one request. Independent of, and a
 * lower-level defense than, the agent workflow's own deadline: that bounds how
 * long the agent may spend; this bounds how many individual tool invocations
 * happen inside any of its steps, and applies equally to the direct
 * tool-calling endpoints (/api/support/assist, /api/chat) that have no agent
 * workflow at all.
 *
 * <h2>Why the counter moved out of this class</h2>
 *
 * It used to be a {@link ThreadLocal} owned here, which was correct exactly as
 * long as one request meant one thread. The agent workflow now runs its two
 * capability steps concurrently, and a thread-local counter under concurrency
 * does not merely lose precision - it resets. Each worker thread would start
 * counting from zero, so a limit of 20 would permit 20 <em>per parallel
 * branch</em>, and nothing would say so: no exception, no log line, no metric.
 * A safety bound that silently stops binding is worse than one that was never
 * added, because it is still on the diagram and still in the documentation.
 *
 * <p>The count now lives in {@link RequestContext}, which is one object shared
 * by every thread working on the request, and reaching a thread that was never
 * given one throws rather than silently starting a fresh count. That is the
 * behaviour to want here: a wrong answer about a security bound should be an
 * error, not a default.
 *
 * <p>{@code @EnableConfigurationProperties} here (rather than relying solely on
 * the app-wide {@code @ConfigurationPropertiesScan}) keeps this class
 * self-sufficient when it's pulled into a narrower Spring context that
 * doesn't scan the whole application - e.g. a {@code @WebMvcTest} slice.
 */
@Component
@EnableConfigurationProperties(GuardrailProperties.class)
public class ToolExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionGuard.class);

    private final GuardrailProperties guardrailProperties;

    public ToolExecutionGuard(GuardrailProperties guardrailProperties) {
        this.guardrailProperties = guardrailProperties;
    }

    /**
     * Records one tool invocation against the current request and throws once
     * the configured limit is exceeded. Called as the first line of every
     * {@code @Tool} method in
     * {@link com.example.aiplatform.ai.tools.SupportTools} - deterministic
     * Java arithmetic the model has no way to influence or bypass.
     */
    public void recordInvocation(String toolName) {
        int count = RequestContextHolder.current().recordToolCall();
        if (count > guardrailProperties.maxToolCallsPerRequest()) {
            log.warn("Tool call limit ({}) exceeded on this request; blocking call to {}",
                    guardrailProperties.maxToolCallsPerRequest(), toolName);
            throw new ToolExecutionLimitExceededException(
                    "Tool call limit exceeded (" + guardrailProperties.maxToolCallsPerRequest()
                            + " calls) for this request; refusing to call " + toolName + " again.");
        }
    }
}
