package com.example.aiplatform.ai.eval;

import com.example.aiplatform.model.SemanticSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast, no-Spring unit tests proving the scoring logic actually discriminates
 * between good and bad cases - the thing that makes the eval harness in
 * RagEvaluationTest trustworthy rather than just "a test that always passes."
 */
class AnswerQualityScorerTest {

    private static final SemanticSearchResult PASSWORD_SOURCE = new SemanticSearchResult(
            "Password Reset Guide",
            "To reset your password, open Settings, go to Security, and select Reset Password.",
            0.9);

    // --- retrieval metrics ---

    @Test
    void perfectRetrievalScoresFullPrecisionAndRecall() {
        RetrievalMetrics metrics = AnswerQualityScorer.retrievalMetrics(
                List.of("Password Reset Guide"), List.of("Password Reset Guide"));

        assertThat(metrics.precision()).isEqualTo(1.0);
        assertThat(metrics.recall()).isEqualTo(1.0);
    }

    @Test
    void badRetrievalWithIrrelevantResultsScoresLowPrecision() {
        RetrievalMetrics metrics = AnswerQualityScorer.retrievalMetrics(
                List.of("Password Reset Guide", "Billing and Payment FAQ", "Inventory Check Procedure"),
                List.of("Password Reset Guide"));

        assertThat(metrics.precision()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        assertThat(metrics.recall()).isEqualTo(1.0);
    }

    @Test
    void missingTheRelevantDocumentScoresZeroRecall() {
        RetrievalMetrics metrics = AnswerQualityScorer.retrievalMetrics(
                List.of("Billing and Payment FAQ"), List.of("Password Reset Guide"));

        assertThat(metrics.recall()).isEqualTo(0.0);
    }

    @Test
    void noExpectedDocumentsAndNothingRetrievedIsTriviallyCorrect() {
        RetrievalMetrics metrics = AnswerQualityScorer.retrievalMetrics(List.of(), List.of());

        assertThat(metrics.precision()).isEqualTo(1.0);
        assertThat(metrics.recall()).isEqualTo(1.0);
    }

    // --- relevance ---

    @Test
    void answerCoveringAllExpectedKeywordsIsFullyRelevant() {
        double score = AnswerQualityScorer.relevanceScore(
                "Go to Settings, then Security, then select Reset Password.",
                List.of("Settings", "Security", "Reset Password"));

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void answerMissingExpectedKeywordsIsPartiallyRelevant() {
        double score = AnswerQualityScorer.relevanceScore(
                "Go to Settings and follow the prompts.",
                List.of("Settings", "Security", "Reset Password"));

        assertThat(score).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.001));
    }

    // --- groundedness ---

    @Test
    void answerBuiltEntirelyFromContextIsFullyGrounded() {
        double score = AnswerQualityScorer.groundednessScore(
                "Go to Settings and select Reset Password.", List.of(PASSWORD_SOURCE));

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void answerWithFabricatedContentNotInContextScoresLowerGroundedness() {
        double groundedScore = AnswerQualityScorer.groundednessScore(
                "Go to Settings and select Reset Password.", List.of(PASSWORD_SOURCE));
        double fabricatedScore = AnswerQualityScorer.groundednessScore(
                "Contact our cryptocurrency billing department for a password refund voucher.",
                List.of(PASSWORD_SOURCE));

        assertThat(fabricatedScore).isLessThan(groundedScore);
    }

    @Test
    void honestRefusalWithNoRetrievedSourcesIsVacuouslyGrounded() {
        double score = AnswerQualityScorer.groundednessScore(
                "I don't have enough information to answer that.", List.of());

        assertThat(score).isEqualTo(1.0);
    }

    // --- citation correctness ---

    @Test
    void citationPointingAtAnActualSourceIsCorrect() {
        boolean correct = AnswerQualityScorer.citationsCorrect(
                "Go to Settings and select Reset Password. [1]", List.of(PASSWORD_SOURCE));

        assertThat(correct).isTrue();
    }

    @Test
    void citationPointingPastTheProvidedSourcesIsHallucinated() {
        boolean correct = AnswerQualityScorer.citationsCorrect(
                "Go to Settings and select Reset Password. [9]", List.of(PASSWORD_SOURCE));

        assertThat(correct).isFalse();
    }

    @Test
    void noCitationAtAllWhenSourcesWereProvidedIsIncorrect() {
        boolean correct = AnswerQualityScorer.citationsCorrect(
                "Go to Settings and select Reset Password.", List.of(PASSWORD_SOURCE));

        assertThat(correct).isFalse();
    }

    @Test
    void noCitationNeededWhenNoSourcesWereProvided() {
        boolean correct = AnswerQualityScorer.citationsCorrect(
                "I don't have enough information to answer that.", List.of());

        assertThat(correct).isTrue();
    }
}
