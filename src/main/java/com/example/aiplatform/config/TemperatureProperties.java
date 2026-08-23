package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-call-site sampling temperature, replacing the single global
 * {@code spring.ai.openai.chat.options.temperature} that previously governed
 * every LLM call in the application.
 *
 * <p>Sampling temperature is not one property of "the model" - it is a
 * property of what a particular call is being asked to do. A single value
 * forced the same setting onto open-ended prose and onto a planner that emits
 * two booleans, and 0.7 is a bad answer for the second: the same question
 * could route to different capabilities on different runs, and structured
 * output failed schema parsing more often than it needed to.
 *
 * <p>Named by intent rather than by number ({@code grounded}, not
 * {@code 0.2}) so call sites read as a statement about the work - see
 * {@link com.example.aiplatform.ai.prompt.SupportPromptBuilder}, which
 * attaches the right one to each prompt it builds. Keeping the choice in the
 * prompt builder means it travels WITH the prompt as portable
 * {@code ChatOptions} rather than being a provider-specific setting the
 * services have to remember to pass.
 */
@ConfigurationProperties(prefix = "app.ai.temperature")
public record TemperatureProperties(

        /** Open-ended prose, where variation is desirable. */
        @DefaultValue("0.7") double conversational,

        /** Answers constrained by retrieved context, tool results, or gathered evidence. */
        @DefaultValue("0.2") double grounded,

        /** Machine-read output - agent planning and schema-constrained structured output. */
        @DefaultValue("0.0") double deterministic
) {
}
