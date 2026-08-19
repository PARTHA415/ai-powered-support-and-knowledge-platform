package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.service.QuestionAnsweringService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagEvaluatorImpl implements RagEvaluator {

    private final SemanticSearchService semanticSearchService;
    private final QuestionAnsweringService questionAnsweringService;
    private final RagProperties ragProperties;

    public RagEvaluatorImpl(SemanticSearchService semanticSearchService,
                             QuestionAnsweringService questionAnsweringService,
                             RagProperties ragProperties) {
        this.semanticSearchService = semanticSearchService;
        this.questionAnsweringService = questionAnsweringService;
        this.ragProperties = ragProperties;
    }

    @Override
    public RagEvaluationResult evaluate(RagEvaluationCase evaluationCase) {
        List<SemanticSearchResult> retrieved =
                semanticSearchService.search(evaluationCase.question(), ragProperties.topK());
        List<String> retrievedTitles = retrieved.stream().map(SemanticSearchResult::documentTitle).toList();
        RetrievalMetrics retrievalMetrics =
                AnswerQualityScorer.retrievalMetrics(retrievedTitles, evaluationCase.expectedRelevantDocumentTitles());

        AskResponse response = questionAnsweringService.answer(evaluationCase.question());

        double relevance = AnswerQualityScorer.relevanceScore(response.answer(), evaluationCase.expectedAnswerKeywords());
        double groundedness = AnswerQualityScorer.groundednessScore(response.answer(), response.sources());
        boolean citationsCorrect = AnswerQualityScorer.citationsCorrect(response.answer(), response.sources());

        return new RagEvaluationResult(
                evaluationCase.id(), retrievalMetrics, relevance, groundedness, citationsCorrect,
                response.answer(), retrievedTitles);
    }
}
