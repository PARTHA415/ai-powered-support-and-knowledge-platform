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
        Map<EvaluationCategory, Double> passRateByCategory
) {

    public static AiEvaluationReport of(List<EvaluationCaseResult> results) {
        if (results.isEmpty()) {
            return new AiEvaluationReport(List.of(), 0.0, Map.of());
        }
        double overall = results.stream().filter(EvaluationCaseResult::passed).count() / (double) results.size();

        Map<EvaluationCategory, Double> byCategory = new LinkedHashMap<>();
        Map<EvaluationCategory, List<EvaluationCaseResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(EvaluationCaseResult::category, LinkedHashMap::new, Collectors.toList()));
        grouped.forEach((category, categoryResults) -> byCategory.put(category,
                categoryResults.stream().filter(EvaluationCaseResult::passed).count() / (double) categoryResults.size()));

        return new AiEvaluationReport(results, overall, byCategory);
    }

    /** A plain-text table - test case | category | score | pass/fail | reason - suitable for logging or a CLI. */
    public String toTable() {
        StringBuilder table = new StringBuilder();
        table.append(String.format("%-45s %-28s %-6s %-6s %s%n", "CASE", "CATEGORY", "SCORE", "PASS", "REASON"));
        for (EvaluationCaseResult result : results) {
            table.append(String.format("%-45s %-28s %-6.2f %-6s %s%n",
                    result.caseId(), result.category(), result.score(), result.passed() ? "PASS" : "FAIL", result.reason()));
        }
        table.append(String.format("%noverall pass rate: %.2f (%d cases)%n", overallPassRate, results.size()));
        passRateByCategory.forEach((category, rate) -> table.append(String.format("  %-28s %.2f%n", category, rate)));
        return table.toString();
    }
}
