package com.example.aiplatform.config;

import com.example.aiplatform.exception.ToolExecutionLimitExceededException;
import com.example.aiplatform.exception.ToolPolicyExceptions;
import com.example.aiplatform.exception.UnauthorizedToolAccessException;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Decides what happens when a {@code @Tool} method throws.
 *
 * <p>Spring AI's default ({@code spring.ai.tools.throw-exception-on-error},
 * which defaults to {@code false}) catches every exception a tool throws and
 * hands its MESSAGE back to the model as the tool result. For most tool
 * failures that is the right behaviour and this configuration keeps it. For
 * two of them it was actively wrong, and silently so:
 *
 * <ul>
 *   <li>{@link UnauthorizedToolAccessException} - access was still correctly
 *       denied, but the denial text became model context and was liable to be
 *       paraphrased straight to the caller, and the
 *       {@code @ExceptionHandler} mapping it to an HTTP status never fired on
 *       any tool-calling path. The application's documented behaviour and its
 *       runtime behaviour disagreed.</li>
 *   <li>{@link ToolExecutionLimitExceededException} - a guardrail whose whole
 *       purpose is to STOP the request. Returned to the model as a string, it
 *       became a suggestion: the model could simply try a different tool, and
 *       the caller never saw the 429 the handler promises.</li>
 * </ul>
 *
 * <p>Both are therefore rethrown, which propagates the original exception
 * unwrapped out of the tool-calling loop and into
 * {@link com.example.aiplatform.exception.GlobalExceptionHandler}. The split
 * is the point: these two are decisions the application has already made and
 * the model does not get a vote on, whereas the two below are information the
 * model can legitimately act on.
 *
 * <ul>
 *   <li>{@code InvalidToolArgumentException} stays non-throwing so the model
 *       can read "orderId must match ORD-####" and retry with a corrected
 *       argument - self-correction is the feature, and failing the whole
 *       request over a malformed argument the model can fix would be worse
 *       for the caller.</li>
 *   <li>{@code ToolResourceNotFoundException} stays non-throwing so the model
 *       can answer "I couldn't find that order" in prose, which is a better
 *       support experience than a bare 404.</li>
 * </ul>
 *
 * <p>Defining this bean replaces Spring AI's auto-configured one, which is
 * annotated {@code @ConditionalOnMissingBean}.
 */
@Configuration
public class ToolExecutionConfig {

    @Bean
    public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        return DefaultToolExecutionExceptionProcessor.builder()
                .alwaysThrow(false)
                .rethrowExceptions(ToolPolicyExceptions.RETHROWN)
                .build();
    }
}
