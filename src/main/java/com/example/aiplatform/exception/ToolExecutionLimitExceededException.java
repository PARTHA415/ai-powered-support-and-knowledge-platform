package com.example.aiplatform.exception;

/**
 * A single request tried to execute more {@code @Tool} calls than
 * app.guardrails.max-tool-calls-per-request allows - the concrete defense
 * against a runaway tool-calling loop (a model that keeps calling tools
 * instead of ever producing a final answer). Thrown from inside a
 * {@code @Tool} method (see {@link com.example.aiplatform.ai.guardrails.ToolExecutionGuard}),
 * so Spring AI's default tool-exception handling turns it into tool-result
 * text the model sees, the same way {@link UnauthorizedToolAccessException}
 * already does - the caller gets an honest "I hit a limit" answer rather
 * than the request hanging or the process resource-exhausting.
 */
public class ToolExecutionLimitExceededException extends RuntimeException {

    public ToolExecutionLimitExceededException(String message) {
        super(message);
    }
}
