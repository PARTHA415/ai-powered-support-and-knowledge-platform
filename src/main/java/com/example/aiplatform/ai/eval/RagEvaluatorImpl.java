package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.EvaluationProperties;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.AskResponse;
import com.example.aiplatform.model.JudgeVerdict;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.service.QuestionAnsweringService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagEvaluatorImpl implements RagEvaluator {

    private final SemanticSearchService semanticSearchService;
    private final QuestionAnsweringService questionAnsweringService;
    private final RagProperties ragProperties;
    private final LlmJudge llmJudge;
    private final EvaluationProperties evaluationProperties;

    public RagEvaluatorImpl(SemanticSearchService semanticSearchService,
                             QuestionAnsweringService questionAnsweringService,
                             RagProperties ragProperties,
                             LlmJudge llmJudge,
                             EvaluationProperties evaluationProperties) {
        this.semanticSearchService = semanticSearchService;
        this.questionAnsweringService = questionAnsweringService;
        this.ragProperties = ragProperties;
        this.llmJudge = llmJudge;
        this.evaluationProperties = evaluationProperties;
    }

    @Override
    public RagEvaluationResult evaluate(RagEvaluationCase evaluationCase) {
        List<SemanticSearchResult> retrieved =
                semanticSearchService.search(evaluationCase.question(), ragProperties.topK());
        List<String> retrievedTitles = retrieved.stream().map(SemanticSearchResult::documentTitle).toList();
        RetrievalMetrics retrievalMetrics =
                AnswerQualityScorer.retrievalMetrics(retrievedTitles, evaluationCase.expectedRelevantDocumentTitles());

        // Deliberately the uncached path: a cached answer would make this
        // measure what the pipeline produced at some earlier point, under an
        // earlier corpus and an earlier prompt, which is the one thing an
        // evaluation must never do. See QuestionAnsweringService.
        AskResponse response = questionAnsweringService.answerWithoutCache(evaluationCase.question());

        double relevance = AnswerQualityScorer.relevanceScore(response.answer(), evaluationCase.expectedAnswerKeywords());
        double groundedness = AnswerQualityScorer.groundednessScore(response.answer(), response.sources());
        boolean citationsCorrect = AnswerQualityScorer.citationsCorrect(response.answer(), response.sources());

        // The deterministic scorers ALWAYS run, judge or no judge. The judge is
        // an additional opinion, not a replacement: when the two disagree that
        // is itself the finding - a high overlap score with a low judge
        // groundedness score is exactly the fabrication-from-the-context's-own-
        // vocabulary case that word counting cannot see.
        JudgeVerdict verdict = evaluationProperties.llmJudgeEnabled()
                ? llmJudge.judge(evaluationCase.question(), response.answer(), response.sources())
                : null;

        return new RagEvaluationResult(
                evaluationCase.id(), retrievalMetrics, relevance, groundedness, citationsCorrect,
                response.answer(), retrievedTitles, verdict);
    }
}
