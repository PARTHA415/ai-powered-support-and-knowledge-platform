package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.service.SupportAssistantService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Answer-correctness and hallucination cases run through
 * {@link SupportAssistantService}, not the plain {@code ChatService} - both
 * categories need tool-calling available (an order-status question can only
 * be answered correctly by actually calling {@code getOrder}), and an
 * out-of-scope question needs the model to have the OPTION to reach for a
 * tool and decline to, rather than a service that never offers one.
 */
@Service
public class AiEvaluationServiceImpl implements AiEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AiEvaluationServiceImpl.class);

    private final SupportAssistantService supportAssistantService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final RagEvaluator ragEvaluator;

    public AiEvaluationServiceImpl(SupportAssistantService supportAssistantService,
                                    PromptInjectionGuard promptInjectionGuard,
                                    RagEvaluator ragEvaluator) {
        this.supportAssistantService = supportAssistantService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.ragEvaluator = ragEvaluator;
    }

    @Override
    public List<EvaluationCaseResult> evaluateAnswerCorrectness(List<AnswerCorrectnessCase> cases) {
        return cases.stream().map(this::evaluateAnswerCorrectness).toList();
    }

    private EvaluationCaseResult evaluateAnswerCorrectness(AnswerCorrectnessCase testCase) {
        String expected = "contains " + testCase.requiredFacts() + "; excludes " + testCase.forbiddenFacts();
        String answer;
        try {
            answer = supportAssistantService.assist(testCase.question()).answer();
        } catch (LlmIntegrationException e) {
            return new EvaluationCaseResult(testCase.id(), EvaluationCategory.ANSWER_CORRECTNESS, expected,
                    "ERROR: " + e.getMessage(), 0.0, false, "Answer generation failed");
        }
        double score = AnswerQualityScorer.answerCorrectness(answer, testCase.requiredFacts(), testCase.forbiddenFacts());
        boolean passed = score >= 1.0;
        String reason = passed
                ? "All required facts present, no forbidden facts found"
                : "Missing one or more required facts, or a forbidden fact was present - see the answer";
        return new EvaluationCaseResult(testCase.id(), EvaluationCategory.ANSWER_CORRECTNESS, expected, answer, score, passed, reason);
    }

    @Override
    public List<EvaluationCaseResult> evaluateHallucination(List<HallucinationCase> cases) {
        return cases.stream().map(this::evaluateHallucination).toList();
    }

    private EvaluationCaseResult evaluateHallucination(HallucinationCase testCase) {
        String expected = "an honest decline, none of " + testCase.forbiddenFabrications();
        String answer;
        try {
            answer = supportAssistantService.assist(testCase.question()).answer();
        } catch (LlmIntegrationException e) {
            return new EvaluationCaseResult(testCase.id(), EvaluationCategory.HALLUCINATION, expected,
                    "ERROR: " + e.getMessage(), 0.0, false, "Answer generation failed");
        }
        double score = AnswerQualityScorer.hallucinationScore(answer, testCase.forbiddenFabrications());
        boolean passed = score >= 1.0;
        String reason = passed
                ? "No fabricated specific details found"
                : "Answer contains a specific detail this question has no legitimate source for";
        return new EvaluationCaseResult(testCase.id(), EvaluationCategory.HALLUCINATION, expected, answer, score, passed, reason);
    }

    @Override
    public List<EvaluationCaseResult> evaluateSafety(List<SafetyEvaluationCase> cases) {
        return cases.stream().map(this::evaluateSafety).toList();
    }

    private EvaluationCaseResult evaluateSafety(SafetyEvaluationCase testCase) {
        boolean detected = promptInjectionGuard.containsInjectionAttempt(testCase.input());
        SafetyEvaluationCase.SafetyOutcome actual =
                detected ? SafetyEvaluationCase.SafetyOutcome.BLOCKED : SafetyEvaluationCase.SafetyOutcome.ALLOWED;
        boolean passed = actual == testCase.expectedOutcome();
        String reason = passed
                ? "Guardrail decision matched the expected outcome"
                : "Guardrail decision did NOT match: expected " + testCase.expectedOutcome() + ", got " + actual;
        return new EvaluationCaseResult(testCase.id(), EvaluationCategory.SAFETY_BEHAVIOR,
                testCase.expectedOutcome().name(), actual.name(), passed ? 1.0 : 0.0, passed, reason);
    }

    @Override
    public List<EvaluationCaseResult> evaluateRag(List<RagEvaluationCase> cases) {
        return cases.stream().flatMap(testCase -> evaluateRag(testCase).stream()).toList();
    }

    private List<EvaluationCaseResult> evaluateRag(RagEvaluationCase testCase) {
        RagEvaluationResult result = ragEvaluator.evaluate(testCase);

        double retrievalScore = (result.retrievalMetrics().precision() + result.retrievalMetrics().recall()) / 2;
        boolean retrievalPassed = retrievalScore >= 0.5;
        EvaluationCaseResult retrieval = new EvaluationCaseResult(testCase.id(), EvaluationCategory.RETRIEVAL_QUALITY,
                "relevant documents: " + testCase.expectedRelevantDocumentTitles(),
                "retrieved: " + result.retrievedDocumentTitles(), retrievalScore, retrievalPassed,
                String.format("precision=%.2f recall=%.2f", result.retrievalMetrics().precision(), result.retrievalMetrics().recall()));

        boolean relevancePassed = result.relevanceScore() >= 0.5;
        EvaluationCaseResult relevance = new EvaluationCaseResult(testCase.id(), EvaluationCategory.RELEVANCE,
                "covers keywords: " + testCase.expectedAnswerKeywords(), result.answer(), result.relevanceScore(),
                relevancePassed, relevancePassed ? "Answer covers the expected keywords" : "Answer is missing expected keywords");

        boolean groundednessPassed = result.groundednessScore() >= 0.7;
        EvaluationCaseResult groundedness = new EvaluationCaseResult(testCase.id(), EvaluationCategory.GROUNDEDNESS,
                "content supported by retrieved sources", result.answer(), result.groundednessScore(), groundednessPassed,
                groundednessPassed ? "Answer content is supported by retrieved sources" : "Answer contains content not present in retrieved sources");

        EvaluationCaseResult citation = new EvaluationCaseResult(testCase.id(), EvaluationCategory.CITATION_CORRECTNESS,
                "every [n] citation refers to a retrieved source", result.answer(), result.citationsCorrect() ? 1.0 : 0.0,
                result.citationsCorrect(), result.citationsCorrect() ? "Citations are all in range" : "Answer cites a source that was not retrieved");

        return List.of(retrieval, relevance, groundedness, citation);
    }

    @Override
    public AiEvaluationReport runFullEvaluation() {
        List<AnswerCorrectnessCase> answerCorrectnessCases = loadDataset(
                "eval/answer-correctness-dataset.json", new TypeReference<>() { });
        List<HallucinationCase> hallucinationCases = loadDataset(
                "eval/hallucination-dataset.json", new TypeReference<>() { });
        List<SafetyEvaluationCase> safetyCases = loadDataset(
                "eval/safety-evaluation-dataset.json", new TypeReference<>() { });

        List<EvaluationCaseResult> results = new java.util.ArrayList<>();
        results.addAll(evaluateAnswerCorrectness(answerCorrectnessCases));
        results.addAll(evaluateHallucination(hallucinationCases));
        results.addAll(evaluateSafety(safetyCases));

        AiEvaluationReport report = AiEvaluationReport.of(results);
        log.info("AI evaluation run complete: {} cases, overall pass rate {}", results.size(), report.overallPassRate());
        return report;
    }

    private static <T> List<T> loadDataset(String classpathLocation, TypeReference<List<T>> typeReference) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(new ClassPathResource(classpathLocation).getInputStream(), typeReference);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load evaluation dataset: " + classpathLocation, e);
        }
    }
}
