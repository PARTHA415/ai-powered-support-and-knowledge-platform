package com.example.aiplatform.controller;

import com.example.aiplatform.ai.eval.AiEvaluationReport;
import com.example.aiplatform.ai.eval.AiEvaluationService;
import com.example.aiplatform.ai.eval.ThresholdCalibrationReport;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the fixed evaluation dataset (Phase 15) against whatever this
 * application instance is currently wired to - the real LLM, the real
 * prompts, the real guardrails. ADMIN-only: this makes real LLM calls (cost,
 * latency) and is an internal quality-check tool, not a customer-facing
 * feature - the same tier as {@code POST /api/embeddings}.
 *
 * Run this once, save the response; change a prompt template or the
 * configured model; run it again; diff the two reports. That comparison -
 * not any single run in isolation - is what "repeatable evaluation" is
 * for.
 */
@RestController
public class EvalController {

    private final AiEvaluationService aiEvaluationService;

    public EvalController(AiEvaluationService aiEvaluationService) {
        this.aiEvaluationService = aiEvaluationService;
    }

    @Operation(summary = "Run the fixed AI evaluation dataset (answer correctness, hallucination, safety) "
            + "against the currently configured LLM and return a scored report - test case, expected "
            + "behavior, actual behavior, score, pass/fail, reason - for each case")
    @PostMapping("/api/eval/run")
    public ResponseEntity<AiEvaluationReport> run() {
        return ResponseEntity.ok(aiEvaluationService.runFullEvaluation());
    }

    @Operation(summary = "Sweep the RAG similarity threshold over the labelled dataset and report precision, "
            + "recall and F1 at each value - so app.rag.similarity-threshold can be chosen from the corpus "
            + "rather than guessed. Makes no LLM calls.")
    @PostMapping("/api/eval/calibrate-threshold")
    public ResponseEntity<ThresholdCalibrationReport> calibrateThreshold() {
        return ResponseEntity.ok(aiEvaluationService.calibrateSimilarityThreshold());
    }
}
