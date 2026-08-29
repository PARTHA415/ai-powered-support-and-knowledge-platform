package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.SemanticSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sweeps the similarity threshold over the labelled RAG dataset and reports
 * what each value would have retrieved.
 *
 * <h2>Why the sweep is nearly free</h2>
 *
 * Retrieval runs ONCE per case; every candidate threshold is then applied to
 * the same retrieved results in memory. Re-running retrieval per threshold
 * would multiply the cost by the number of steps and would change nothing,
 * because the threshold is a filter applied after retrieval, not a parameter of
 * it. One embedding call per case, no LLM calls, and the whole sweep completes
 * in the time one RAG answer would take.
 *
 * <h2>What it measures, precisely</h2>
 *
 * The predicate under test is the real one -
 * {@link SemanticSearchResult#isRelevantAt(double)}, lexical exemption
 * included - not a reimplementation of it. Calibrating an approximation of the
 * production rule would produce a number that is optimal for something the
 * platform does not do.
 *
 * <h2>What it cannot tell you</h2>
 *
 * Document-level relevance is not answer quality. A threshold that maximises F1
 * against human-labelled relevant documents is a strong starting point and not
 * a proof: the model may answer better with one marginal extra chunk than the
 * labels suggest, or worse. Treat the output as the range worth testing against
 * the full evaluation run, not as the answer to it.
 */
@Service
public class SimilarityThresholdCalibrator {

    private static final Logger log = LoggerFactory.getLogger(SimilarityThresholdCalibrator.class);

    private static final double SWEEP_START = 0.0;
    private static final double SWEEP_END = 0.95;
    private static final double SWEEP_STEP = 0.05;

    private final SemanticSearchService semanticSearchService;
    private final RagProperties ragProperties;

    public SimilarityThresholdCalibrator(SemanticSearchService semanticSearchService, RagProperties ragProperties) {
        this.semanticSearchService = semanticSearchService;
        this.ragProperties = ragProperties;
    }

    public ThresholdCalibrationReport calibrate(List<RagEvaluationCase> cases) {
        if (cases.isEmpty()) {
            return new ThresholdCalibrationReport(0, ragProperties.similarityThreshold(),
                    ragProperties.similarityThreshold(), List.of());
        }

        // Retrieve once per case, then score every threshold against the same
        // results. Retrieval is wider than topK here on purpose: the sweep needs
        // to see the chunks a high threshold would reject, and a topK-limited
        // retrieval has already thrown some of them away.
        List<Retrieved> retrievedPerCase = new ArrayList<>(cases.size());
        for (RagEvaluationCase evaluationCase : cases) {
            List<SemanticSearchResult> results =
                    semanticSearchService.search(evaluationCase.question(), ragProperties.candidateLimit());
            retrievedPerCase.add(new Retrieved(results, new LinkedHashSet<>(
                    evaluationCase.expectedRelevantDocumentTitles())));
        }

        List<ThresholdCalibrationReport.ThresholdScore> sweep = new ArrayList<>();
        double bestThreshold = ragProperties.similarityThreshold();
        double bestF1 = -1;
        for (double threshold = SWEEP_START; threshold <= SWEEP_END + 1e-9; threshold += SWEEP_STEP) {
            double rounded = Math.round(threshold * 100) / 100.0;
            ThresholdCalibrationReport.ThresholdScore score = scoreAt(rounded, retrievedPerCase);
            sweep.add(score);
            if (score.f1() > bestF1) {
                bestF1 = score.f1();
                bestThreshold = rounded;
            }
        }

        log.info("Similarity threshold calibration over {} case(s): best F1 {} at threshold {} (currently {})",
                cases.size(), bestF1, bestThreshold, ragProperties.similarityThreshold());
        return new ThresholdCalibrationReport(cases.size(), bestThreshold,
                ragProperties.similarityThreshold(), sweep);
    }

    /**
     * Precision and recall are averaged per case rather than pooled across all
     * cases. Pooling would let one question with many relevant documents
     * dominate the score of a dataset whose other questions have one each -
     * the average of per-case scores treats every question as equally
     * important, which is what a support platform's users would say.
     */
    private ThresholdCalibrationReport.ThresholdScore scoreAt(double threshold, List<Retrieved> retrievedPerCase) {
        double precisionSum = 0;
        double recallSum = 0;
        for (Retrieved retrieved : retrievedPerCase) {
            Set<String> admitted = new LinkedHashSet<>();
            for (SemanticSearchResult result : retrieved.results()) {
                if (result.isRelevantAt(threshold)) {
                    admitted.add(result.documentTitle());
                }
            }
            long truePositives = admitted.stream().filter(retrieved.expected()::contains).count();
            precisionSum += admitted.isEmpty() ? (retrieved.expected().isEmpty() ? 1.0 : 0.0)
                    : (double) truePositives / admitted.size();
            recallSum += retrieved.expected().isEmpty() ? 1.0
                    : (double) truePositives / retrieved.expected().size();
        }
        double precision = precisionSum / retrievedPerCase.size();
        double recall = recallSum / retrievedPerCase.size();
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new ThresholdCalibrationReport.ThresholdScore(threshold, precision, recall, f1);
    }

    private record Retrieved(List<SemanticSearchResult> results, Set<String> expected) {
    }
}
