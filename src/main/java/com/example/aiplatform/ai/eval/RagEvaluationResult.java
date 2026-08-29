package com.example.aiplatform.ai.eval;

import com.example.aiplatform.model.JudgeVerdict;

import java.util.List;

/**
 * One RAG case's scores.
 *
 * <p>{@code judgeVerdict} is nullable: the LLM-judge tier is off by default
 * ({@code app.eval.llm-judge-enabled}) because it costs a model call per case
 * and makes the run non-deterministic. Null means "not asked", which is
 * distinct from {@link JudgeVerdict#unavailable} - "asked, and the judge could
 * not answer". Both are distinct from a low score, and a report that conflated
 * any two of the three would be lying about one of them.
 */
public record RagEvaluationResult(
        String caseId,
        RetrievalMetrics retrievalMetrics,
        double relevanceScore,
        double groundednessScore,
        boolean citationsCorrect,
        String answer,
        List<String> retrievedDocumentTitles,
        JudgeVerdict judgeVerdict
) {

    public RagEvaluationResult(String caseId, RetrievalMetrics retrievalMetrics, double relevanceScore,
                                double groundednessScore, boolean citationsCorrect, String answer,
                                List<String> retrievedDocumentTitles) {
        this(caseId, retrievalMetrics, relevanceScore, groundednessScore, citationsCorrect, answer,
                retrievedDocumentTitles, null);
    }

    public boolean hasJudgeVerdict() {
        return judgeVerdict != null && judgeVerdict.isAvailable();
    }
}
