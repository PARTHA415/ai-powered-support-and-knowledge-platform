package com.example.aiplatform.ai.eval;

import java.util.List;

/**
 * Stricter than {@link RagEvaluationCase}'s "relevance" (does the answer
 * cover the right topic): correctness asks whether the answer states the
 * SPECIFIC right fact and does not state a specific wrong one.
 * forbiddenFacts exists because a wrong-but-confident answer ("your order is
 * CANCELLED" when it's actually SHIPPED) is a worse failure than a vague
 * one, and plain keyword-presence scoring can't tell "mentions SHIPPED"
 * apart from "mentions SHIPPED among three other statuses it's hedging
 * across" - checking for the WRONG facts too catches that.
 */
public record AnswerCorrectnessCase(
        String id,
        String question,
        List<String> requiredFacts,
        List<String> forbiddenFacts
) {
}
