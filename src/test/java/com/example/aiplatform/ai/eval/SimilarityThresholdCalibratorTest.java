package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.config.TestRagProperties;
import com.example.aiplatform.model.SemanticSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimilarityThresholdCalibratorTest {

    @Mock
    private SemanticSearchService semanticSearchService;

    /**
     * The sweep must cost one retrieval per case, not one per threshold. The
     * threshold is a filter applied AFTER retrieval, so re-retrieving for each
     * candidate value would multiply the cost by twenty and change nothing.
     */
    @Test
    void retrievalRunsOncePerCaseNotOncePerCandidateThreshold() {
        when(semanticSearchService.search(anyString(), any(Integer.class)))
                .thenReturn(List.of(result("Kafka Guide", 0.8)));
        SimilarityThresholdCalibrator calibrator =
                new SimilarityThresholdCalibrator(semanticSearchService, TestRagProperties.defaults());

        ThresholdCalibrationReport report = calibrator.calibrate(List.of(
                new RagEvaluationCase("c1", "kafka lag", List.of("Kafka Guide"), List.of()),
                new RagEvaluationCase("c2", "consumer offsets", List.of("Kafka Guide"), List.of())));

        verify(semanticSearchService, times(2)).search(anyString(), any(Integer.class));
        assertThat(report.sweep()).hasSizeGreaterThan(10);
        assertThat(report.caseCount()).isEqualTo(2);
    }

    /**
     * A threshold low enough to admit an irrelevant document costs precision;
     * one high enough to reject the relevant one costs recall. The best F1
     * should land between the two, which is the whole reason for measuring
     * rather than guessing.
     */
    @Test
    void theBestThresholdSeparatesTheRelevantDocumentFromTheIrrelevantOne() {
        when(semanticSearchService.search(anyString(), any(Integer.class))).thenReturn(List.of(
                result("Kafka Guide", 0.80),
                result("Office Directions", 0.30)));
        SimilarityThresholdCalibrator calibrator =
                new SimilarityThresholdCalibrator(semanticSearchService, TestRagProperties.defaults());

        ThresholdCalibrationReport report = calibrator.calibrate(List.of(
                new RagEvaluationCase("c1", "kafka lag", List.of("Kafka Guide"), List.of())));

        assertThat(report.bestThreshold()).isGreaterThan(0.30).isLessThanOrEqualTo(0.80);
        assertThat(report.currentThreshold()).isEqualTo(0.5);
        assertThat(report.toTable()).contains("THRESHOLD").contains("best");
    }

    /**
     * The calibrator scores the REAL relevance predicate, lexical exemption
     * included. A lexical match is admitted at every threshold, so raising the
     * threshold cannot exclude it - calibrating an approximation of the
     * production rule would produce a number optimal for something the platform
     * does not do.
     */
    @Test
    void aLexicalMatchIsAdmittedAtEveryThresholdBecauseThatIsTheProductionRule() {
        when(semanticSearchService.search(anyString(), any(Integer.class))).thenReturn(List.of(
                new SemanticSearchResult("Error Code Table", "ERR-4711 means...", 0.10, true, 0.5)));
        SimilarityThresholdCalibrator calibrator =
                new SimilarityThresholdCalibrator(semanticSearchService, TestRagProperties.defaults());

        ThresholdCalibrationReport report = calibrator.calibrate(List.of(
                new RagEvaluationCase("c1", "ERR-4711", List.of("Error Code Table"), List.of())));

        assertThat(report.sweep()).allSatisfy(score -> assertThat(score.recall()).isEqualTo(1.0));
    }

    @Test
    void anEmptyDatasetReportsTheCurrentThresholdAndNothingElse() {
        SimilarityThresholdCalibrator calibrator =
                new SimilarityThresholdCalibrator(semanticSearchService, TestRagProperties.defaults());

        ThresholdCalibrationReport report = calibrator.calibrate(List.of());

        assertThat(report.caseCount()).isZero();
        assertThat(report.sweep()).isEmpty();
        assertThat(report.bestThreshold()).isEqualTo(report.currentThreshold());
    }

    private static SemanticSearchResult result(String title, double similarity) {
        return new SemanticSearchResult(title, "content", similarity);
    }
}
