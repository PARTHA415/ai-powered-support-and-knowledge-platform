package com.example.aiplatform.ai.eval;

import java.util.List;

/**
 * The "proper evaluation framework" Phase 15 asks for: a real Spring bean,
 * callable from a real endpoint ({@code POST /api/eval/run}) against the
 * REAL, currently-wired application - not a test-only harness. In a
 * production deployment (real {@code LlmClientService}, real
 * {@code SemanticSearchService}), running the exact same fixed dataset
 * before and after a prompt or model change and diffing the two
 * {@link AiEvaluationReport}s is what "repeatable, comparable evaluation"
 * means here. In a Testcontainers test (see the Phase 15 docs), the same
 * methods run against mocked LLM/embedding boundaries for a fully
 * deterministic CI check.
 *
 * Covers six of the nine tracked categories directly: retrieval quality,
 * relevance, groundedness, and citation correctness (delegated to
 * {@link RagEvaluator}), plus answer correctness, hallucination, and safety
 * behavior. Tool selection and tool argument correctness are deliberately
 * NOT here - see {@link ToolSelectionScorer}'s Javadoc for why.
 */
public interface AiEvaluationService {

    List<EvaluationCaseResult> evaluateAnswerCorrectness(List<AnswerCorrectnessCase> cases);

    List<EvaluationCaseResult> evaluateHallucination(List<HallucinationCase> cases);

    List<EvaluationCaseResult> evaluateSafety(List<SafetyEvaluationCase> cases);

    List<EvaluationCaseResult> evaluateRag(List<RagEvaluationCase> cases);

    /**
     * Loads the fixed datasets bundled on the classpath
     * ({@code eval/answer-correctness-dataset.json},
     * {@code eval/hallucination-dataset.json},
     * {@code eval/safety-evaluation-dataset.json}) and runs all three
     * categories, combined into one {@link AiEvaluationReport}. Deliberately
     * excludes the RAG categories - meaningfully evaluating retrieval
     * requires a known, pre-ingested knowledge base, which this method
     * cannot assume the running application has (see the Phase 15 docs for
     * how to include RAG in a full run).
     */
    AiEvaluationReport runFullEvaluation();
}
