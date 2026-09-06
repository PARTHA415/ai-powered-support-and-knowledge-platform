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

    // --- answer correctness (required / forbidden facts) ---

    private static final List<String> STOCK_FACT =
            List.of("out of stock|not in stock|0 units|zero units|no units|0 in stock");

    @Test
    void requiredFactStatedInTheModelsOwnCasingStillCounts() {
        double score = AnswerQualityScorer.answerCorrectness(
                "The status of order ORD-1001 is \"Shipped.\"", List.of("SHIPPED"), List.of("CANCELLED"));

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void forbiddenEnumTokenDoesNotFireOnOrdinaryProse() {
        // The regression this whole matching rule exists for: "paid" inside
        // "has been paid yet" is not the business value PAID.
        double score = AnswerQualityScorer.answerCorrectness(
                "The payment status for order ORD-1002 is \"PENDING\", and no amount has been paid yet.",
                List.of("PENDING"), List.of("PAID", "FAILED", "REFUNDED"));

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void forbiddenEnumTokenActuallyStatedCollapsesTheScore() {
        double score = AnswerQualityScorer.answerCorrectness(
                "Order ORD-1002 is PENDING, and the invoice is PAID.",
                List.of("PENDING"), List.of("PAID"));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void forbiddenFactDoesNotMatchInsideALongerWord() {
        double score = AnswerQualityScorer.answerCorrectness(
                "The order used a prepaid balance and is PENDING.", List.of("PENDING"), List.of("PAID"));

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void onlySomeRequiredFactsPresentScoresTheFraction() {
        double score = AnswerQualityScorer.answerCorrectness(
                "Customer CUST-1001 is on the GOLD tier.", List.of("GOLD", "CUST-1001", "renewal"), List.of());

        assertThat(score).isEqualTo(2.0 / 3.0);
    }

    // --- alternative wordings ---

    @Test
    void anyOfTheAcceptedWordingsSatisfiesARequiredFact() {
        // The exact answer that failed the eval while being factually right.
        double numeric = AnswerQualityScorer.answerCorrectness(
                "There are currently 0 units of product PROD-2002 in stock.", STOCK_FACT, List.of("42"));
        // ...and the phrasing the dataset originally demanded.
        double phrased = AnswerQualityScorer.answerCorrectness(
                "PROD-2002 is out of stock.", STOCK_FACT, List.of("42"));

        assertThat(numeric).isEqualTo(1.0);
        assertThat(phrased).isEqualTo(1.0);
    }

    @Test
    void anAnswerMatchingNoneOfTheAlternativesStillFails() {
        // Claiming stock exists must remain a failure - the fix must not turn
        // the case into one that passes regardless of what the model says.
        double score = AnswerQualityScorer.answerCorrectness(
                "PROD-2002 has 7 units available.", STOCK_FACT, List.of("42"));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void quotingTheWrongProductsStockIsStillCaught() {
        // 42 is PROD-2001's quantity; reporting it for PROD-2002 is the
        // product-confusion failure the forbidden fact is there to catch.
        double score = AnswerQualityScorer.answerCorrectness(
                "PROD-2002 has 42 units in stock.", STOCK_FACT, List.of("42"));

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void theSeparatorIsNotRegexTheDatasetCanInject() {
        // "a|b" must mean "a or b", never a compiled alternation over
        // attacker-shaped input - and a bare "." must not match any character.
        double score = AnswerQualityScorer.answerCorrectness(
                "The tier is GOLD.", List.of("GOLD|.*"), List.of());

        assertThat(score).isEqualTo(1.0);
        assertThat(AnswerQualityScorer.answerCorrectness("The tier is SILVER.", List.of(".*"), List.of()))
                .isEqualTo(0.0);
    }

    // --- hallucination ---

    @Test
    void honestDeclineIsNotAHallucination() {
        double score = AnswerQualityScorer.hallucinationScore(
                "I don't have any information about that order.", List.of("ORD-9999", "SHIPPED"));

        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void inventingASpecificDetailIsAHallucination() {
        double score = AnswerQualityScorer.hallucinationScore(
                "Order ORD-9999 was SHIPPED on Tuesday.", List.of("ORD-9999", "SHIPPED"));

        assertThat(score).isEqualTo(0.0);
    }

    // --- groundedness: content words, compared as stems ---

    /** The chunk actually retrieved for the kafka-consumer-lag evaluation case. */
    private static final SemanticSearchResult KAFKA_RUNBOOK = new SemanticSearchResult(
            "Troubleshooting Kafka Consumer Failures",
            "Kafka consumer failures usually fall into four categories. First, consumer lag: when the "
                    + "consumer cannot keep up with the producer, lag grows without bound. Check consumer-group "
                    + "lag with kafka-consumer-groups.sh --describe --group <id>. Raise max.poll.records or add "
                    + "partitions and consumers. Second, rebalancing storms: if max.poll.interval.ms is shorter "
                    + "than the time your handler needs, the broker evicts the consumer mid-batch and the group "
                    + "rebalances forever.",
            0.9);

    @Test
    void inflectedFormsOfTheSameWordCountAsGrounded() {
        double score = AnswerQualityScorer.groundednessScore(
                "Consider raising max.poll.records, or adding partitions, so the lag does not grow.",
                List.of(KAFKA_RUNBOOK));

        // "raising"/"Raise", "adding"/"add", "grow"/"grows" are the same claims.
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    void theRealKafkaAnswerClearsTheGroundednessBar() {
        // Verbatim: the answer that scored 0.5 against a 0.7 bar while being
        // entirely supported by this very chunk.
        double score = AnswerQualityScorer.groundednessScore(
                "Your Kafka consumer might be falling behind due to consumer lag, which occurs when the "
                        + "consumer cannot keep up with the producer, causing the lag to grow without bound. To "
                        + "address this issue, you can check the consumer-group lag using the command "
                        + "`kafka-consumer-groups.sh --describe --group <id>`. If you find that lag is indeed the "
                        + "problem, consider raising `max.poll.records` or adding more partitions and consumers to "
                        + "help the consumer keep pace with the producer [2].",
                List.of(KAFKA_RUNBOOK));

        assertThat(score).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void inventedDomainVocabularyIsStillUngrounded() {
        // The check must not have been defined away: filler removal cannot be
        // allowed to launder an answer whose technical content is invented.
        double score = AnswerQualityScorer.groundednessScore(
                "You should consider raising the quantum flux threshold and enabling the holographic "
                        + "sharding coprocessor to address this issue.",
                List.of(KAFKA_RUNBOOK));

        assertThat(score).isLessThan(0.5);
    }

    @Test
    void aFabricatedAnswerScoresBelowAGroundedOneOnTheSameSource() {
        double grounded = AnswerQualityScorer.groundednessScore(
                "Raise max.poll.records or add partitions and consumers.", List.of(KAFKA_RUNBOOK));
        double fabricated = AnswerQualityScorer.groundednessScore(
                "Restore the billing ledger from the cryptocurrency escrow snapshot.", List.of(KAFKA_RUNBOOK));

        assertThat(fabricated).isLessThan(grounded);
        assertThat(grounded).isEqualTo(1.0);
    }

    @Test
    void anAnswerOfPureFillerAssertsNothingToVerify() {
        double score = AnswerQualityScorer.groundednessScore(
                "Well, that would be something you might want to consider.", List.of(KAFKA_RUNBOOK));

        assertThat(score).isEqualTo(1.0);
    }
}
