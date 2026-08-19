package com.example.aiplatform.ai.structured;

import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.model.ConfidenceLevel;
import com.example.aiplatform.model.SupportAnswer;
import com.example.aiplatform.model.SupportCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportAnswerConverterTest {

    private final SupportAnswerConverter converter = new SupportAnswerConverter();

    @Test
    void formatInstructionsDescribeTheTargetSchema() {
        String format = converter.formatInstructions();

        assertThat(format)
                .contains("answer")
                .contains("category")
                .contains("confidence")
                .contains("needsHumanEscalation");
    }

    @Test
    void parsesWellFormedModelResponse() {
        String rawResponse = """
                {
                  "answer": "Go to Settings > Security > Reset Password.",
                  "category": "ACCOUNT",
                  "confidence": "HIGH",
                  "needsHumanEscalation": false
                }
                """;

        SupportAnswer answer = converter.parse(rawResponse);

        assertThat(answer).isEqualTo(new SupportAnswer(
                "Go to Settings > Security > Reset Password.",
                SupportCategory.ACCOUNT,
                ConfidenceLevel.HIGH,
                false));
    }

    @Test
    void parsesModelResponseWrappedInMarkdownCodeFence() {
        String rawResponse = """
                ```json
                {
                  "answer": "Restart the consumer group.",
                  "category": "TECHNICAL",
                  "confidence": "MEDIUM",
                  "needsHumanEscalation": true
                }
                ```
                """;

        SupportAnswer answer = converter.parse(rawResponse);

        assertThat(answer.category()).isEqualTo(SupportCategory.TECHNICAL);
        assertThat(answer.needsHumanEscalation()).isTrue();
    }

    @Test
    void rejectsMalformedJson() {
        String rawResponse = "I think the answer is probably related to billing.";

        assertThatThrownBy(() -> converter.parse(rawResponse))
                .isInstanceOf(LlmIntegrationException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsJsonWithAnEnumValueOutsideTheSchema() {
        String rawResponse = """
                {
                  "answer": "Not sure how to classify this.",
                  "category": "NOT_A_REAL_CATEGORY",
                  "confidence": "HIGH",
                  "needsHumanEscalation": false
                }
                """;

        assertThatThrownBy(() -> converter.parse(rawResponse))
                .isInstanceOf(LlmIntegrationException.class);
    }
}
