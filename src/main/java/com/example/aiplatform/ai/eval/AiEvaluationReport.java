package com.example.aiplatform.ai.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The report Phase 15 asks for: one row per evaluated case (test case,
 * expected behavior, actual behavior, score, pass/fail, reason), plus a
 * pass-rate summary overall and per {@link EvaluationCategory}. Comparing
 * two of these - one from before a prompt/model change, one from after,
 * both produced by running the exact same fixed dataset - is what "make the
 * evaluation repeatable so model/prompt changes can be compared" means in
 * practice: the dataset and scoring stay fixed, only the pipeline under
 * test changes between two runs.
 */
public record AiEvaluationReport(
        List<EvaluationCaseResult> results,
        double overallPassRate,
        Map<EvaluationCategory, Double> passRateByCategory,
        long totalDurationMillis,
        long slowestCaseDurationMillis
) {

    public static AiEvaluationReport of(List<EvaluationCaseResult> results) {
        if (results.isEmpty()) {
            return new AiEvaluationReport(List.of(), 0.0, Map.of(), 0, 0);
        }
        double overall = results.stream().filter(EvaluationCaseResult::passed).count() / (double) results.size();

        Map<EvaluationCategory, Double> byCategory = new LinkedHashMap<>();
        Map<EvaluationCategory, List<EvaluationCaseResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(EvaluationCaseResult::category, LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((category, categoryResults) -> byCategory.put(category,
                categoryResults.stream().filter(EvaluationCaseResult::passed).count() / (double) categoryResults.size()));

        // Latency is reported alongside quality because a prompt change that
        // improves every score while tripling response time is still a
        // regression - and the report could not previously show that.
        long total = results.stream().mapToLong(EvaluationCaseResult::durationMillis).sum();
        long slowest = results.stream().mapToLong(EvaluationCaseResult::durationMillis).max().orElse(0);

        return new AiEvaluationReport(results, overall, byCategory, total, slowest);
    }

    /** A plain-text table - test case | category | score | pass/fail | reason - suitable for logging or a CLI. */
    public String toTable() {
        StringBuilder table = new StringBuilder();
        table.append(String.format("%-45s %-28s %-6s %-6s %-8s %s%n",
                "CASE", "CATEGORY", "SCORE", "PASS", "MS", "REASON"));
        for (EvaluationCaseResult result : results) {
            table.append(String.format("%-45s %-28s %-6.2f %-6s %-8d %s%n",
                    result.caseId(), result.category(), result.score(), result.passed() ? "PASS" : "FAIL",
                    result.durationMillis(), result.reason()));
        }
        table.append(String.format("%noverall pass rate: %.2f (%d cases), total %dms, slowest case %dms%n",
                overallPassRate, results.size(), totalDurationMillis, slowestCaseDurationMillis));
        passRateByCategory.forEach((category, rate) -> table.append(String.format("  %-28s %.2f%n", category, rate)));
        return table.toString();
    }
}
