package com.example.aiplatform.ai.eval;

import java.util.List;

/**
 * A single labeled example in a RAG evaluation dataset: a question, the
 * documents a human judged relevant to it, and the characteristics a correct
 * answer should exhibit. This is the ground truth everything else in this
 * package is scored against - unlike a unit test's expected value, these
 * labels are a judgment call that has to be made by a person, not derived
 * from the code.
 */
public record RagEvaluationCase(
        String id,
        String question,
        List<String> expectedRelevantDocumentTitles,
        List<String> expectedAnswerKeywords
) {
}
