package com.example.aiplatform.ai.structured;

import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.model.SupportAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * Turns {@link SupportAnswer} into the JSON-schema format instructions Spring AI
 * derives from it, and turns the model's raw text response back into a
 * {@link SupportAnswer}. The schema/parsing enforcement here is "best effort":
 * the model is asked (via the format instructions) to comply, not forced to -
 * so parsing failures are a real, expected case, not a bug.
 */
@Component
public class SupportAnswerConverter {

    private static final Logger log = LoggerFactory.getLogger(SupportAnswerConverter.class);

    private final BeanOutputConverter<SupportAnswer> delegate = new BeanOutputConverter<>(SupportAnswer.class);

    public String formatInstructions() {
        return delegate.getFormat();
    }

    public SupportAnswer parse(String rawResponse) {
        try {
            return delegate.convert(rawResponse);
        } catch (RuntimeException e) {
            log.error("Failed to parse structured LLM response into SupportAnswer");
            throw new LlmIntegrationException(
                    "The LLM returned a response that did not match the expected format", e);
        }
    }
}
