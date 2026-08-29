package com.example.aiplatform.ai.rag;

import com.example.aiplatform.observability.AiPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CitationValidator validator = new CitationValidator(new AiPipelineMetrics(meterRegistry));

    @Test
    void anAnswerCitingOnlyRealSourcesIsReturnedUnchanged() {
        String answer = "Restart the consumer [1] and check the offsets [2].";

        assertThat(validator.validate(answer, 2, "")).isEqualTo(answer);
    }

    @Test
    void aCitationPastTheEndOfTheSourceListIsRemoved() {
        String answer = "Restart the consumer [1] and rotate the key [7].";

        String validated = validator.validate(answer, 2, "");

        assertThat(validated).contains("[1]").doesNotContain("[7]");
        assertThat(validated).isEqualTo("Restart the consumer [1] and rotate the key.");
    }

    @Test
    void aZeroIndexedCitationIsOutOfRangeBecauseSourcesAreNumberedFromOne() {
        assertThat(validator.validate("See [0].", 3, "")).doesNotContain("[0]");
    }

    @Test
    void anAnswerWithNoCitationsIsUntouched() {
        String answer = "I don't have enough information to answer that.";

        assertThat(validator.validate(answer, 0, "")).isEqualTo(answer);
    }

    /**
     * The agent's evidence block numbers its excerpts [KB1], [KB2]. Validating
     * one path's markers with the other's prefix would strip every legitimate
     * citation, so the prefix is a parameter rather than a constant.
     */
    @Test
    void thePrefixedAgentFormatIsValidatedIndependentlyOfThePlainFormat() {
        String answer = "According to [KB1] restart it; [KB4] says otherwise.";

        String validated = validator.validate(answer, 2, "KB");

        assertThat(validated).contains("[KB1]").doesNotContain("[KB4]");
    }

    @Test
    void aPlainCitationIsNotStrippedWhenValidatingThePrefixedFormat() {
        assertThat(validator.validate("Cost is [1] dollar per unit.", 0, "KB"))
                .isEqualTo("Cost is [1] dollar per unit.");
    }

    /**
     * A fabricated citation is a real signal, not just something to clean up:
     * a sustained non-zero rate here is a prompt or retrieval problem to go and
     * fix, and it can only be noticed if it is counted.
     */
    @Test
    void strippingACitationRecordsASafetyBoundMetric() {
        validator.validate("Claim [9].", 1, "");

        assertThat(meterRegistry.get("ai.safety.bound.triggered")
                .tag("reason", "invalid_citation_stripped").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aCitationIndexTooLargeForAnIntegerIsTreatedAsOutOfRange() {
        assertThat(validator.validate("See [99999999999999999999].", 3, ""))
                .doesNotContain("99999999999999999999");
    }

    @Test
    void anEmptyOrNullAnswerIsReturnedAsIs() {
        assertThat(validator.validate(null, 2, "")).isNull();
        assertThat(validator.validate("   ", 2, "")).isEqualTo("   ");
    }
}
