package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.model.JudgeVerdict;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SupportAssistantService supportAssistantService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final RagEvaluator ragEvaluator;
    private final EvaluationReportStore reportStore;
    private final SimilarityThresholdCalibrator similarityThresholdCalibrator;
    private final com.example.aiplatform.config.EvaluationProperties evaluationProperties;

    public AiEvaluationServiceImpl(SupportAssistantService supportAssistantService,
                                    PromptInjectionGuard promptInjectionGuard,
                                    RagEvaluator ragEvaluator,
                                    EvaluationReportStore reportStore,
                                    SimilarityThresholdCalibrator similarityThresholdCalibrator,
                                    com.example.aiplatform.config.EvaluationProperties evaluationProperties) {
        this.supportAssistantService = supportAssistantService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.ragEvaluator = ragEvaluator;
        this.reportStore = reportStore;
        this.similarityThresholdCalibrator = similarityThresholdCalibrator;
        this.evaluationProperties = evaluationProperties;
    }

    @Override
    public List<EvaluationCaseResult> evaluateAnswerCorrectness(List<AnswerCorrectnessCase> cases) {
        return cases.stream().map(this::evaluateAnswerCorrectness).toList();
    }

    private EvaluationCaseResult evaluateAnswerCorrectness(AnswerCorrectnessCase testCase) {
        String expected = "contains " + testCase.requiredFacts() + "; excludes " + testCase.forbiddenFacts();
        String answer;
        long startNanos = System.nanoTime();
        try {
            answer = supportAssistantService.assist(testCase.question()).answer();
        } catch (LlmIntegrationException e) {
            return new EvaluationCaseResult(testCase.id(), EvaluationCategory.ANSWER_CORRECTNESS, expected,
                    "ERROR: " + e.getMessage(), 0.0, false, "Answer generation failed", elapsedMillis(startNanos));
        }
        double score = AnswerQualityScorer.answerCorrectness(answer, testCase.requiredFacts(), testCase.forbiddenFacts());
        boolean passed = score >= 1.0;
        String reason = passed
                ? "All required facts present, no forbidden facts found"
                : "Missing one or more required facts, or a forbidden fact was present - see the answer";
        return new EvaluationCaseResult(testCase.id(), EvaluationCategory.ANSWER_CORRECTNESS, expected, answer, score,
                passed, reason, elapsedMillis(startNanos));
    }

    @Override
    public List<EvaluationCaseResult> evaluateHallucination(List<HallucinationCase> cases) {
        return cases.stream().map(this::evaluateHallucination).toList();
    }

    private EvaluationCaseResult evaluateHallucination(HallucinationCase testCase) {
        String expected = "an honest decline, none of " + testCase.forbiddenFabrications();
        String answer;
        long startNanos = System.nanoTime();
        try {
            answer = supportAssistantService.assist(testCase.question()).answer();
        } catch (LlmIntegrationException e) {
            return new EvaluationCaseResult(testCase.id(), EvaluationCategory.HALLUCINATION, expected,
                    "ERROR: " + e.getMessage(), 0.0, false, "Answer generation failed", elapsedMillis(startNanos));
        }
        double score = AnswerQualityScorer.hallucinationScore(answer, testCase.forbiddenFabrications());
        boolean passed = score >= 1.0;
        String reason = passed
                ? "No fabricated specific details found"
                : "Answer contains a specific detail this question has no legitimate source for";
        return new EvaluationCaseResult(testCase.id(), EvaluationCategory.HALLUCINATION, expected, answer, score,
                passed, reason, elapsedMillis(startNanos));
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
        // A pattern match, not a model call - no meaningful latency to report.
        return EvaluationCaseResult.instant(testCase.id(), EvaluationCategory.SAFETY_BEHAVIOR,
                testCase.expectedOutcome().name(), actual.name(), passed ? 1.0 : 0.0, passed, reason);
    }

    @Override
    public List<EvaluationCaseResult> evaluateRag(List<RagEvaluationCase> cases) {
        return cases.stream().flatMap(testCase -> evaluateRag(testCase).stream()).toList();
    }

    private List<EvaluationCaseResult> evaluateRag(RagEvaluationCase testCase) {
        long startNanos = System.nanoTime();
        RagEvaluationResult result = ragEvaluator.evaluate(testCase);
        // One measured duration for the whole case - retrieval plus generation
        // happen together and there is no useful way to attribute the wall
        // clock to the four scores derived from that single run.
        long durationMillis = elapsedMillis(startNanos);

        double retrievalScore = (result.retrievalMetrics().precision() + result.retrievalMetrics().recall()) / 2;
        boolean retrievalPassed = retrievalScore >= 0.5;
        EvaluationCaseResult retrieval = new EvaluationCaseResult(testCase.id(), EvaluationCategory.RETRIEVAL_QUALITY,
                "relevant documents: " + testCase.expectedRelevantDocumentTitles(),
                "retrieved: " + result.retrievedDocumentTitles(), retrievalScore, retrievalPassed,
                String.format("precision=%.2f recall=%.2f", result.retrievalMetrics().precision(),
                        result.retrievalMetrics().recall()),
                durationMillis);

        boolean relevancePassed = result.relevanceScore() >= 0.5;
        EvaluationCaseResult relevance = new EvaluationCaseResult(testCase.id(), EvaluationCategory.RELEVANCE,
                "covers keywords: " + testCase.expectedAnswerKeywords(), result.answer(), result.relevanceScore(),
                relevancePassed,
                relevancePassed ? "Answer covers the expected keywords" : "Answer is missing expected keywords",
                durationMillis);

        boolean groundednessPassed = result.groundednessScore() >= 0.7;
        EvaluationCaseResult groundedness = new EvaluationCaseResult(testCase.id(), EvaluationCategory.GROUNDEDNESS,
                "content supported by retrieved sources", result.answer(), result.groundednessScore(),
                groundednessPassed,
                groundednessPassed ? "Answer content is supported by retrieved sources"
                        : "Answer contains content not present in retrieved sources",
                durationMillis);

        EvaluationCaseResult citation = new EvaluationCaseResult(testCase.id(),
                EvaluationCategory.CITATION_CORRECTNESS,
                "every [n] citation refers to a retrieved source", result.answer(),
                result.citationsCorrect() ? 1.0 : 0.0, result.citationsCorrect(),
                result.citationsCorrect() ? "Citations are all in range"
                        : "Answer cites a source that was not retrieved",
                durationMillis);


        List<EvaluationCaseResult> results = new java.util.ArrayList<>(
                List.of(retrieval, relevance, groundedness, citation));

        // Only emitted when the judge actually ran and actually answered.
        // Absent rows are honest: a category that silently reported 0 for every
        // case because the tier was disabled would look like a total quality
        // collapse in the one artefact people read to detect quality collapses.
        if (result.hasJudgeVerdict()) {
            JudgeVerdict verdict = result.judgeVerdict();
            boolean judgeGroundednessPassed = verdict.groundedness() >= 0.7;
            results.add(new EvaluationCaseResult(testCase.id(), EvaluationCategory.JUDGE_GROUNDEDNESS,
                    "every claim entailed by the retrieved context", result.answer(), verdict.groundedness(),
                    judgeGroundednessPassed, verdict.groundednessReason(), durationMillis));

            boolean judgeRelevancePassed = verdict.relevance() >= 0.7;
            results.add(new EvaluationCaseResult(testCase.id(), EvaluationCategory.JUDGE_RELEVANCE,
                    "answers the question that was asked", result.answer(), verdict.relevance(),
                    judgeRelevancePassed, verdict.relevanceReason(), durationMillis));
        }

        return results;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Runs every category the harness supports, RAG included.
     *
     * <p>RAG was previously missing here. {@link #evaluateRag} existed and
     * worked, but nothing outside a test ever called it and no RAG dataset
     * shipped - so the endpoint built to compare prompt and model changes could
     * not detect a retrieval regression, the single most likely thing to break
     * when tuning a RAG pipeline.
     *
     * <p>The RAG dataset is optional. Its cases name specific document titles,
     * which only mean something if the matching corpus has been ingested into
     * THIS instance; on an empty or unrelated knowledge base every case would
     * score zero and report a regression that is really just a missing corpus.
     * Absent dataset means the section is skipped and said so, rather than
     * silently reported as failure. See the shipped
     * {@code eval/knowledge-base-fixture.json} for the corpus these cases
     * assume.
     */
    @Override
    public AiEvaluationReport runFullEvaluation() {
        List<AnswerCorrectnessCase> answerCorrectnessCases = loadDataset(
                "eval/answer-correctness-dataset.json", new TypeReference<>() { });
        List<HallucinationCase> hallucinationCases = loadDataset(
                "eval/hallucination-dataset.json", new TypeReference<>() { });
        List<SafetyEvaluationCase> safetyCases = loadDataset(
                "eval/safety-evaluation-dataset.json", new TypeReference<>() { });
        List<RagEvaluationCase> ragCases = loadOptionalDataset(
                "eval/rag-evaluation-dataset.json", new TypeReference<>() { });

        List<EvaluationCaseResult> results = new java.util.ArrayList<>();
        results.addAll(evaluateAnswerCorrectness(capped(answerCorrectnessCases)));
        results.addAll(evaluateHallucination(capped(hallucinationCases)));
        results.addAll(evaluateSafety(safetyCases));
        if (ragCases.isEmpty()) {
            log.warn("No RAG evaluation dataset found - retrieval quality, relevance, groundedness and citation "
                    + "correctness are NOT covered by this run");
        } else {
            results.addAll(evaluateRag(capped(ragCases)));
        }

        AiEvaluationReport report = AiEvaluationReport.of(results);
        log.info("AI evaluation run complete: {} cases, overall pass rate {}, total latency {}ms",
                results.size(), report.overallPassRate(), report.totalDurationMillis());
        reportStore.save(report);
        return report;
    }


    @Override
    public ThresholdCalibrationReport calibrateSimilarityThreshold() {
        List<RagEvaluationCase> ragCases = loadOptionalDataset(
                "eval/rag-evaluation-dataset.json", new TypeReference<>() { });
        if (ragCases.isEmpty()) {
            log.warn("No RAG evaluation dataset found - there is nothing to calibrate the similarity "
                    + "threshold against. Calibration needs labelled questions and the corpus they refer to.");
        }
        return similarityThresholdCalibrator.calibrate(capped(ragCases));
    }

    /**
     * Truncates a dataset to {@code app.eval.max-cases}.
     *
     * <p>Every case in an evaluation run is at least one billed call, executed
     * in a loop over a file that lives in the repository. A dataset that grows
     * past what anyone intended to spend is not a hypothetical - it is the
     * ordinary result of several people adding cases - and a ceiling turns that
     * into a truncated run and a warning rather than into a bill.
     */
    private <T> List<T> capped(List<T> cases) {
        if (cases.size() <= evaluationProperties.maxCases()) {
            return cases;
        }
        log.warn("Evaluation dataset has {} cases, over the app.eval.max-cases ceiling of {} - running the "
                + "first {} only. Raise the ceiling deliberately if the whole set is meant to run.",
                cases.size(), evaluationProperties.maxCases(), evaluationProperties.maxCases());
        return cases.subList(0, evaluationProperties.maxCases());
    }

    private static <T> List<T> loadDataset(String classpathLocation, TypeReference<List<T>> typeReference) {
        try {
            return OBJECT_MAPPER.readValue(new ClassPathResource(classpathLocation).getInputStream(), typeReference);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load evaluation dataset: " + classpathLocation, e);
        }
    }

    private static <T> List<T> loadOptionalDataset(String classpathLocation, TypeReference<List<T>> typeReference) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            return List.of();
        }
        return loadDataset(classpathLocation, typeReference);
    }
}
