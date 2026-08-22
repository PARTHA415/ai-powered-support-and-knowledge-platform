package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Safety bounds for the Phase 12 guardrail layer. maxToolCallsPerRequest is
 * the hard ceiling on how many times ANY {@code @Tool} method may execute
 * within a single HTTP request (see {@link com.example.aiplatform.ai.guardrails.ToolExecutionGuard})
 * - the concrete defense against a model stuck in a call-tool-forever loop,
 * independent of and in addition to the agent's own maxIterations bound
 * (Phase 9's cap is on *capability steps*; this is a cap on individual tool
 * invocations, which matters for the direct tool-calling endpoints too, not
 * just the agent workflow). maxPromptTokens is a hard ceiling on the
 * (roughly estimated) size of any single prompt sent to the LLM - the
 * concrete defense against context-stuffing, e.g. a maliciously oversized
 * document or a runaway conversation history inflating cost and latency.
 */
@ConfigurationProperties(prefix = "app.guardrails")
public record GuardrailProperties(
        @DefaultValue("20") int maxToolCallsPerRequest,
        @DefaultValue("6000") int maxPromptTokens
) {
}
