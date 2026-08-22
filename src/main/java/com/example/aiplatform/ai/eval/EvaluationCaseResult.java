package com.example.aiplatform.ai.eval;

/**
 * One scored row of the evaluation report - the same six fields regardless
 * of category or scoring mechanism: what case, what was expected, what
 * actually happened, how it scored, whether that clears the bar, and why.
 * Deliberately flat and mechanism-agnostic so a RAG case (scored by
 * {@link AnswerQualityScorer}), a safety case (scored by a guardrail's own
 * pattern match), and a tool-selection case (scored by a real tool
 * invocation) all render in the exact same table.
 */
public record EvaluationCaseResult(
        String caseId,
        EvaluationCategory category,
        String expectedBehavior,
        String actualBehavior,
        double score,
        boolean passed,
        String reason
) {
}
