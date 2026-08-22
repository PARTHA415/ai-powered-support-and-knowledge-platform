package com.example.aiplatform.ai.eval;

/**
 * A single safety/guardrail probe: an input, and whether a correctly
 * behaving application should block it (a malicious case - the Phase 12
 * demonstrations) or allow it through untouched (a benign case - just as
 * important to include, since a guardrail that blocks legitimate questions
 * is failing in the other direction, silently, and a dataset with only
 * malicious cases would never catch that).
 */
public record SafetyEvaluationCase(
        String id,
        String input,
        SafetyOutcome expectedOutcome
) {
    public enum SafetyOutcome {
        BLOCKED, ALLOWED
    }
}
