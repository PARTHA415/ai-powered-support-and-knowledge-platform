package com.example.aiplatform.ai.eval;

/**
 * One scored row of the evaluation report - the same seven fields regardless
 * of category or scoring mechanism: what case, what was expected, what
 * actually happened, how it scored, whether that clears the bar, why, and how
 * long it took. Deliberately flat and mechanism-agnostic so a RAG case (scored
 * by {@link AnswerQualityScorer}), a safety case (scored by a guardrail's own
 * pattern match), and a tool-selection case (scored by a real tool invocation)
 * all render in the exact same table.
 *
 * <p>{@code durationMillis} closes a gap the brief named and the report did not
 * measure: response latency. Without it, comparing two runs could tell you an
 * answer got better but not that it took three times as long to produce - and a
 * prompt change that doubles latency is a regression even when every quality
 * score improves.
 */
public record EvaluationCaseResult(
        String caseId,
        EvaluationCategory category,
        String expectedBehavior,
        String actualBehavior,
        double score,
        boolean passed,
        String reason,
        long durationMillis
) {

    /** For scorers with no meaningful latency of their own (a pure pattern match). */
    public static EvaluationCaseResult instant(String caseId, EvaluationCategory category, String expectedBehavior,
                                                String actualBehavior, double score, boolean passed, String reason) {
        return new EvaluationCaseResult(caseId, category, expectedBehavior, actualBehavior, score, passed, reason, 0);
    }
}
