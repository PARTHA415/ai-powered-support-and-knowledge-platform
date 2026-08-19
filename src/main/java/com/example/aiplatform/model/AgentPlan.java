package com.example.aiplatform.model;

/**
 * The agent workflow's one planning decision, produced by a single
 * structured-output LLM call (same mechanism as Phase 3's SupportAnswer) -
 * not free-form reasoning, a schema-constrained yes/no/yes decision. Field
 * order matters here: reasoning is asked for first so the model has to
 * articulate why before committing to the booleans, a small prompt-
 * engineering nudge toward better decisions (a lightweight structured
 * alternative to unstructured chain-of-thought).
 */
public record AgentPlan(
        String reasoning,
        boolean needsKnowledgeBase,
        boolean needsBusinessTool
) {
}
