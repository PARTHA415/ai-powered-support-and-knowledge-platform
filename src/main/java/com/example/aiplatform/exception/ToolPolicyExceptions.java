package com.example.aiplatform.exception;

import java.util.List;

/**
 * The tool failures the model does not get a vote on - decisions the
 * application has already made, which must terminate the request rather than
 * become text the model reads and works around.
 *
 * <p>This list exists in one place because two independent mechanisms have to
 * agree on it, and a silent disagreement between them would be hard to spot:
 *
 * <ul>
 *   <li>{@link com.example.aiplatform.config.ToolExecutionConfig} passes it to
 *       Spring AI's exception processor, which rethrows a matching exception
 *       out of the tool-calling loop instead of feeding its message back to
 *       the model.</li>
 *   <li>{@link com.example.aiplatform.ai.llm.SpringAiLlmClientService} uses
 *       {@link #isPolicyViolation(Throwable)} to let exactly those exceptions
 *       through its catch-all instead of wrapping them as a provider
 *       failure.</li>
 * </ul>
 *
 * <p>If only the first were configured, the rethrow would work and then be
 * immediately undone: the exception would surface as a 502 Bad Gateway
 * blaming the LLM provider for the application's own authorization decision.
 */
public final class ToolPolicyExceptions {

    /**
     * Matched with {@code isAssignableFrom}, so subclasses are covered too.
     */
    public static final List<Class<? extends RuntimeException>> RETHROWN = List.of(
            UnauthorizedToolAccessException.class,
            ToolExecutionLimitExceededException.class);

    private ToolPolicyExceptions() {
    }

    /** True if the throwable, or anything in its cause chain, is one of {@link #RETHROWN}. */
    public static boolean isPolicyViolation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            for (Class<? extends RuntimeException> type : RETHROWN) {
                if (type.isInstance(current)) {
                    return true;
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
