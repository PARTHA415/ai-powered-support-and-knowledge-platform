package com.example.aiplatform.ai.eval;

/**
 * The dimensions the harness evaluates, each scored the same way
 * ({@link EvaluationCaseResult}) regardless of which mechanism produces it -
 * a deterministic scorer against a canned answer, a real tool call, or a
 * pattern-matched guardrail decision. See the Phase 15 docs for exactly
 * which mechanism covers which category and why.
 */
public enum EvaluationCategory {
    ANSWER_CORRECTNESS,
    RELEVANCE,
    GROUNDEDNESS,
    HALLUCINATION,
    RETRIEVAL_QUALITY,
    CITATION_CORRECTNESS,
    JUDGE_GROUNDEDNESS,
    JUDGE_RELEVANCE,
    TOOL_SELECTION,
    TOOL_ARGUMENT_CORRECTNESS,
    SAFETY_BEHAVIOR
}
