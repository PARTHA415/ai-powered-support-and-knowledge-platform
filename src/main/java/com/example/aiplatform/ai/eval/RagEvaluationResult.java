package com.example.aiplatform.ai.eval;

import java.util.List;

public record RagEvaluationResult(
        String caseId,
        RetrievalMetrics retrievalMetrics,
        double relevanceScore,
        double groundednessScore,
        boolean citationsCorrect,
        String answer,
        List<String> retrievedDocumentTitles
) {
}
