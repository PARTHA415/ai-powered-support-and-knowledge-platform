package com.example.aiplatform.ai.eval;

import java.util.List;

/**
 * What the similarity threshold would have done, at every value it could have
 * had, over the labelled RAG dataset.
 *
 * <p>The threshold was the least principled number in the RAG configuration: a
 * round 0.5 that nobody measured, deciding for every question which retrieved
 * chunks the model is allowed to see. Set too high it starves answers of
 * context they needed; too low it fills the prompt with noise and invites the
 * model to ground a claim in something irrelevant. Both failures look the same
 * from outside - a worse answer - which is why guessing produces a number
 * nobody can defend and nobody dares change.
 *
 * <p>This report replaces the guess with a sweep. It is cheap and repeatable:
 * retrieval runs once per case and every candidate threshold is scored against
 * the SAME retrieved results, so the whole sweep costs one embedding call per
 * case and no LLM calls at all.
 *
 * @param bestThreshold the value with the highest F1 across the dataset
 * @param currentThreshold what is configured now, scored on the same data, so
 *                         the report answers "should I change it" rather than
 *                         only "what is best"
 */
public record ThresholdCalibrationReport(
        int caseCount,
        double bestThreshold,
        double currentThreshold,
        List<ThresholdScore> sweep
) {

    /**
     * @param f1 the harmonic mean of precision and recall. Used rather than
     *           either alone because each is trivially maximised on its own -
     *           a threshold of 0 admits everything and scores perfect recall,
     *           a threshold of 1 admits nothing and cannot be wrong about what
     *           it admitted. Only their combination describes a useful setting.
     */
    public record ThresholdScore(double threshold, double precision, double recall, double f1) {
    }

    public String toTable() {
        StringBuilder table = new StringBuilder();
        table.append(String.format("%-12s %-12s %-12s %-12s%n", "THRESHOLD", "PRECISION", "RECALL", "F1"));
        for (ThresholdScore score : sweep) {
            String marker = score.threshold() == bestThreshold ? "  <- best" : "";
            table.append(String.format("%-12.2f %-12.3f %-12.3f %-12.3f%s%n",
                    score.threshold(), score.precision(), score.recall(), score.f1(), marker));
        }
        table.append(String.format("%n%d case(s); currently configured: %.2f, best on this data: %.2f%n",
                caseCount, currentThreshold, bestThreshold));
        return table.toString();
    }
}
