package com.example.aiplatform.ai.rag;

import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.config.TestRagProperties;
import com.example.aiplatform.model.HybridChunkMatch;
import com.example.aiplatform.model.SemanticSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionRerankerTest {

    private final Reranker reranker = new ReciprocalRankFusionReranker(TestRagProperties.defaults());

    /**
     * The property that makes fusion worth doing. Two independent searches
     * agreeing is stronger evidence than one search being confident, and RRF is
     * chosen precisely because it ranks that way - a chunk placed second by
     * both beats a chunk placed first by only one.
     */
    @Test
    void aChunkFoundByBothSearchesOutranksOneFoundFirstByOnlyOne() {
        HybridChunkMatch denseOnlyWinner = candidate(1L, 10L, "Dense Winner", 0.05, 1, null);
        HybridChunkMatch foundByBoth = candidate(2L, 11L, "Agreed By Both", 0.30, 2, 2);

        List<SemanticSearchResult> ranked = reranker.rerank(List.of(denseOnlyWinner, foundByBoth), 5);

        assertThat(ranked).extracting(SemanticSearchResult::documentTitle)
                .containsExactly("Agreed By Both", "Dense Winner");
    }

    /**
     * A lexical-only hit keeps its real cosine similarity - the repository
     * computes one for every candidate - and is flagged so the relevance rule
     * can admit it without a semantic score corroborating it.
     */
    @Test
    void aLexicalOnlyMatchIsFlaggedAndKeepsItsRealCosineSimilarity() {
        HybridChunkMatch lexicalOnly = candidate(1L, 10L, "Error Code Table", 0.72, null, 1);

        List<SemanticSearchResult> ranked = reranker.rerank(List.of(lexicalOnly), 5);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).lexicalMatch()).isTrue();
        assertThat(ranked.get(0).similarity()).isEqualTo(1 - 0.72);
        assertThat(ranked.get(0).fusedScore()).isGreaterThan(0.0);
    }

    /**
     * Overlapping chunks from one document are near-duplicates, so an uncapped
     * ranking lets a single document fill every slot with paraphrases of itself
     * and spend the context window learning nothing after the first.
     */
    @Test
    void onlyTheConfiguredNumberOfChunksPerDocumentSurviveWhenThereIsAnAlternative() {
        List<HybridChunkMatch> candidates = List.of(
                candidate(1L, 10L, "Kafka Runbook", 0.10, 1, null),
                candidate(2L, 10L, "Kafka Runbook", 0.11, 2, null),
                candidate(3L, 10L, "Kafka Runbook", 0.12, 3, null),
                candidate(4L, 11L, "Consumer Guide", 0.40, 4, null));

        List<SemanticSearchResult> ranked = reranker.rerank(candidates, 3);

        assertThat(ranked).hasSize(3);
        assertThat(ranked).extracting(SemanticSearchResult::documentTitle)
                .containsExactly("Kafka Runbook", "Kafka Runbook", "Consumer Guide");
    }

    /**
     * The cap is a preference, not a hard limit. On a corpus with one relevant
     * document, returning fewer results than asked for would be a worse answer -
     * and the cap exists to improve answers.
     */
    @Test
    void theCapIsRelaxedRatherThanReturningFewerResultsThanRequested() {
        List<HybridChunkMatch> allFromOneDocument = List.of(
                candidate(1L, 10L, "Only Document", 0.10, 1, null),
                candidate(2L, 10L, "Only Document", 0.11, 2, null),
                candidate(3L, 10L, "Only Document", 0.12, 3, null),
                candidate(4L, 10L, "Only Document", 0.13, 4, null));

        List<SemanticSearchResult> ranked = reranker.rerank(allFromOneDocument, 4);

        assertThat(ranked).hasSize(4);
    }

    /**
     * The top-up pass appends what the cap deferred, which can outscore
     * something admitted earlier - so the final list is re-sorted rather than
     * assumed to still be in order.
     */
    @Test
    void resultsAreInDescendingFusedScoreOrderAfterTheCapTopsUp() {
        List<HybridChunkMatch> candidates = List.of(
                candidate(1L, 10L, "Doc A", 0.10, 1, 1),
                candidate(2L, 10L, "Doc A", 0.11, 2, 2),
                candidate(3L, 10L, "Doc A", 0.12, 3, 3),
                candidate(4L, 11L, "Doc B", 0.90, 20, null));

        List<SemanticSearchResult> ranked = reranker.rerank(candidates, 4);

        assertThat(ranked).isSortedAccordingTo(
                (left, right) -> Double.compare(right.fusedScore(), left.fusedScore()));
    }

    @Test
    void weightingTheLexicalSearchHigherChangesTheOrdering() {
        RagProperties lexicalHeavy = new RagProperties(220, 40, 32, 5, 0.5, true, 4, 60, 0.1, 5.0, 2);
        Reranker weighted = new ReciprocalRankFusionReranker(lexicalHeavy);

        List<SemanticSearchResult> ranked = weighted.rerank(List.of(
                candidate(1L, 10L, "Dense Top", 0.05, 1, null),
                candidate(2L, 11L, "Lexical Top", 0.80, null, 1)), 5);

        assertThat(ranked).extracting(SemanticSearchResult::documentTitle)
                .containsExactly("Lexical Top", "Dense Top");
    }

    @Test
    void noCandidatesProducesNoResults() {
        assertThat(reranker.rerank(List.of(), 5)).isEmpty();
    }

    private static HybridChunkMatch candidate(long chunkId, long documentId, String title, double distance,
                                               Integer denseRank, Integer lexicalRank) {
        return new HybridChunkMatch(chunkId, documentId, title, "content of " + chunkId, distance,
                denseRank, lexicalRank, lexicalRank == null ? 0.0 : 1.0 / lexicalRank);
    }
}
