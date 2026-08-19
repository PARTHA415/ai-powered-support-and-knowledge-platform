package com.example.aiplatform.ai.eval;

/**
 * Runs one labeled {@link RagEvaluationCase} through the real pipeline
 * (retrieval via {@link com.example.aiplatform.ai.rag.SemanticSearchService},
 * generation via {@link com.example.aiplatform.service.QuestionAnsweringService})
 * and scores the result. Retrieval metrics are computed against the raw
 * top-K retrieval, independent of the similarity threshold, because
 * retrieval quality (chunking + embedding + vector search) and threshold
 * tuning are separate concerns - conflating them would hide whether a
 * retrieval miss is a search problem or a threshold problem.
 */
public interface RagEvaluator {

    RagEvaluationResult evaluate(RagEvaluationCase evaluationCase);
}
