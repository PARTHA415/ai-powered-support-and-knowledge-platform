package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Safety bounds for the agent workflow.
 *
 * <h2>Why maxIterations is gone</h2>
 *
 * It capped capability steps at 5, against a list that could hold at most 2 -
 * one knowledge-base search and one business-tool round. The bound was
 * unreachable by construction, which made it and its metric dead code that
 * nonetheless read, in configuration and in documentation, as the concrete
 * defense against a runaway agent loop. There was no loop for it to bound.
 *
 * <p>An unreachable safety bound is worse than a missing one: it answers the
 * question "what stops this running away" with something that has never fired
 * and never can, and the answer looks satisfactory. Removing it makes the real
 * situation visible - the capabilities are a fixed, planned set, and what
 * actually needs bounding is time and tool calls, both of which are bounded
 * elsewhere and for real.
 *
 * <p>If the workflow ever becomes genuinely iterative - a plan-act-observe loop
 * that decides what to do next based on what came back - an iteration cap comes
 * back with it, and it will be reachable.
 *
 * @param timeoutSeconds a wall-clock budget for the whole agent request,
 *                       enforced around each LLM call rather than only between
 *                       steps. The distinction is the difference between a
 *                       stated bound and a real one: checked only between
 *                       steps, three sequential calls at up to ~50s each could
 *                       take 150 seconds while every check passed, because no
 *                       check ever ran while a call was in flight.
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(
        @DefaultValue("30") double timeoutSeconds,

        /**
         * How many agent requests may have capability steps running
         * concurrently. Small on purpose: this pool exists to run two
         * independent steps of ONE request at the same time, not to add
         * request-level concurrency, which the servlet container already
         * provides. Sizing it generously would mostly buy more simultaneous
         * pressure on the LLM provider and the circuit breaker.
         */
        @DefaultValue("8") int maxConcurrentSteps
) {
}
