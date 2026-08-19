package com.example.aiplatform.ai.eval;

import java.util.List;

/**
 * Aggregates a batch of {@link RagEvaluationResult}s into summary numbers -
 * the thing you'd actually watch trend over time in CI as chunking, top-K,
 * threshold, or prompt changes are made, rather than reading every individual
 * case result.
 */
public record RagEvaluationReport(
        int caseCount,
        double averagePrecision,
        double averageRecall,
        double averageRelevance,
        double averageGroundedness,
        double citationAccuracy,
        List<RagEvaluationResult> results
) {

    public static RagEvaluationReport summarize(List<RagEvaluationResult> results) {
        if (results.isEmpty()) {
            return new RagEvaluationReport(0, 0, 0, 0, 0, 0, List.of());
        }
        double avgPrecision = results.stream().mapToDouble(r -> r.retrievalMetrics().precision()).average().orElse(0);
        double avgRecall = results.stream().mapToDouble(r -> r.retrievalMetrics().recall()).average().orElse(0);
        double avgRelevance = results.stream().mapToDouble(RagEvaluationResult::relevanceScore).average().orElse(0);
        double avgGroundedness = results.stream().mapToDouble(RagEvaluationResult::groundednessScore).average().orElse(0);
        double citationAccuracy = results.stream().filter(RagEvaluationResult::citationsCorrect).count() / (double) results.size();
        return new RagEvaluationReport(
                results.size(), avgPrecision, avgRecall, avgRelevance, avgGroundedness, citationAccuracy, results);
    }
}
