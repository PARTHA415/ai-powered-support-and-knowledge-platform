package com.example.aiplatform.ai.eval;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Aggregates a batch of {@link RagEvaluationResult}s into summary numbers -
 * the thing you'd actually watch trend over time in CI as chunking, top-K,
 * threshold, or prompt changes are made, rather than reading every individual
 * case result.
 *
 * <p>The judge averages are reported separately from the lexical ones rather
 * than blended into them. Blending would destroy the most useful signal the
 * pair produces: when word-overlap groundedness is high and judge groundedness
 * is low, that is the fabrication-from-the-context's-own-vocabulary case the
 * lexical scorer structurally cannot see. A single combined number would
 * average that finding away.
 *
 * @param averageJudgeGroundedness -1 when the judge did not run, which is
 *                                 distinct from 0 ("it ran and the answers were
 *                                 ungrounded") and must stay distinct - a
 *                                 disabled tier reported as zero looks exactly
 *                                 like a total quality collapse.
 */
public record RagEvaluationReport(
        int caseCount,
        double averagePrecision,
        double averageRecall,
        double averageRelevance,
        double averageGroundedness,
        double citationAccuracy,
        double averageJudgeGroundedness,
        double averageJudgeRelevance,
        int judgedCaseCount,
        List<RagEvaluationResult> results
) {

    private static final double NOT_JUDGED = -1.0;

    public static RagEvaluationReport summarize(List<RagEvaluationResult> results) {
        if (results.isEmpty()) {
            return new RagEvaluationReport(0, 0, 0, 0, 0, 0, NOT_JUDGED, NOT_JUDGED, 0, List.of());
        }
        double avgPrecision = results.stream().mapToDouble(r -> r.retrievalMetrics().precision()).average().orElse(0);
        double avgRecall = results.stream().mapToDouble(r -> r.retrievalMetrics().recall()).average().orElse(0);
        double avgRelevance = results.stream().mapToDouble(RagEvaluationResult::relevanceScore).average().orElse(0);
        double avgGroundedness = results.stream().mapToDouble(RagEvaluationResult::groundednessScore).average().orElse(0);
        double citationAccuracy = results.stream().filter(RagEvaluationResult::citationsCorrect).count()
                / (double) results.size();

        List<RagEvaluationResult> judged = results.stream().filter(RagEvaluationResult::hasJudgeVerdict).toList();
        OptionalDouble judgeGroundedness = judged.stream()
                .mapToDouble(result -> result.judgeVerdict().groundedness()).average();
        OptionalDouble judgeRelevance = judged.stream()
                .mapToDouble(result -> result.judgeVerdict().relevance()).average();

        return new RagEvaluationReport(results.size(), avgPrecision, avgRecall, avgRelevance, avgGroundedness,
                citationAccuracy, judgeGroundedness.orElse(NOT_JUDGED), judgeRelevance.orElse(NOT_JUDGED),
                judged.size(), results);
    }

    /** Whether the LLM-judge tier contributed to this report at all. */
    public boolean wasJudged() {
        return judgedCaseCount > 0;
    }
}
