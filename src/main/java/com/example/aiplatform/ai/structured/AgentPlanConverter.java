package com.example.aiplatform.ai.structured;

import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.model.AgentPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * The agent workflow's planning step, expressed with the same structured-
 * output mechanism as {@link SupportAnswerConverter} - a schema-constrained
 * decision, not free-form reasoning the workflow would need to interpret.
 */
@Component
public class AgentPlanConverter {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanConverter.class);

    private final BeanOutputConverter<AgentPlan> delegate = new BeanOutputConverter<>(AgentPlan.class);

    public String formatInstructions() {
        return delegate.getFormat();
    }

    public AgentPlan parse(String rawResponse) {
        try {
            return delegate.convert(rawResponse);
        } catch (RuntimeException e) {
            log.error("Failed to parse structured LLM response into AgentPlan");
            throw new LlmIntegrationException(
                    "The LLM returned a response that did not match the expected format", e);
        }
    }
}
