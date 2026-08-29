package com.example.aiplatform.repository;

import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.model.HybridChunkMatch;
import com.example.aiplatform.model.RetrievalFilter;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hybrid retrieval against real Postgres with pgvector AND the full-text
 * index - the half of the feature that cannot be tested with mocks, because
 * what is being asserted is what the two Postgres indexes actually return for
 * a given query.
 */
@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest
@Transactional
class HybridRetrievalIT {

    private static final int EMBEDDING_DIMENSIONS = 1536;
    private static final String MODEL = "text-embedding-3-small";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    /**
     * The case hybrid retrieval exists for. The chunk containing the exact
     * term is embedded far away from the query vector, so dense-only retrieval
     * ranks it last or not at all - and full-text search finds it immediately.
     * An operator searching an error code is doing exactly this.
     */
    @Test
    void anExactTermIsFoundByTheLexicalSearchEvenWhenItsEmbeddingIsFarAway() {
        Document document = documentRepository.save(new Document("Error Code Table", "kb/errors.md"));
        DocumentChunk exactTerm = documentChunkRepository.save(new DocumentChunk(document, 0,
                "Broker returns UNKNOWN_TOPIC_OR_PARTITION when the topic is missing."));
        DocumentChunk semanticallyClose = documentChunkRepository.save(new DocumentChunk(document, 1,
                "Consumers rebalance when group membership changes."));
        documentChunkRepository.saveEmbedding(exactTerm.getId(), unitVector(5), MODEL);
        documentChunkRepository.saveEmbedding(semanticallyClose.getId(), unitVector(0), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "UNKNOWN_TOPIC_OR_PARTITION", 10, MODEL, RetrievalFilter.none());

        HybridChunkMatch found = byChunkId(candidates, exactTerm.getId());
        assertThat(found.matchedLexically()).isTrue();
        assertThat(found.lexicalRank()).isEqualTo(1);
        assertThat(found.lexicalScore()).isGreaterThan(0.0);
    }

    /**
     * Every candidate carries a real cosine distance, including one only the
     * lexical search found - otherwise "similarity" would silently mean
     * something different depending on which search returned the row.
     */
    @Test
    void aLexicalOnlyCandidateStillCarriesARealCosineDistance() {
        Document document = documentRepository.save(new Document("Lexical Only", null));
        DocumentChunk chunk = documentChunkRepository.save(
                new DocumentChunk(document, 0, "The zookeeper quorum lost its leader."));
        documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(7), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "zookeeper quorum", 10, MODEL, RetrievalFilter.none());

        HybridChunkMatch found = byChunkId(candidates, chunk.getId());
        assertThat(found.matchedLexically()).isTrue();
        // Orthogonal unit vectors: cosine distance 1.0, and a real number
        // rather than a placeholder.
        assertThat(found.distance()).isCloseTo(1.0, Offset.offset(1e-6));
    }

    @Test
    void aChunkFoundByBothSearchesCarriesBothRanks() {
        Document document = documentRepository.save(new Document("Both", null));
        DocumentChunk chunk = documentChunkRepository.save(
                new DocumentChunk(document, 0, "Consumer lag grows when the poll loop stalls."));
        documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(0), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "consumer lag poll loop", 10, MODEL, RetrievalFilter.none());

        HybridChunkMatch found = byChunkId(candidates, chunk.getId());
        assertThat(found.matchedDensely()).isTrue();
        assertThat(found.matchedLexically()).isTrue();
    }

    /**
     * The security-critical case. The audience exclusion is applied inside BOTH
     * halves of the query, so a customer cannot reach an internal runbook by
     * typing a distinctive term out of it - which is exactly what a lexical
     * branch that forgot the filter would allow, and it would be invisible in
     * any test that only exercised the vector path.
     */
    @Test
    void theAudienceExclusionAppliesToTheLexicalHalfOfTheQueryToo() {
        Document internal = documentRepository.save(
                new Document("Internal Runbook", "kb/internal.md", Map.of("audience", "internal")));
        DocumentChunk chunk = documentChunkRepository.save(
                new DocumentChunk(internal, 0, "Rotate the SIGNINGKEYALPHA secret every quarter."));
        documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(9), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "SIGNINGKEYALPHA", 10, MODEL,
                new RetrievalFilter(Map.of(), Map.of("audience", "internal")));

        assertThat(candidates).noneMatch(candidate -> candidate.chunkId().equals(chunk.getId()));
    }

    @Test
    void aCallerRequestedMetadataFilterAlsoNarrowsBothHalves() {
        Document kafka = documentRepository.save(
                new Document("Kafka Doc", null, Map.of("product", "kafka")));
        Document redis = documentRepository.save(
                new Document("Redis Doc", null, Map.of("product", "redis")));
        DocumentChunk kafkaChunk = documentChunkRepository.save(
                new DocumentChunk(kafka, 0, "Rebalance storms affect throughput."));
        DocumentChunk redisChunk = documentChunkRepository.save(
                new DocumentChunk(redis, 0, "Rebalance storms affect throughput."));
        documentChunkRepository.saveEmbedding(kafkaChunk.getId(), unitVector(0), MODEL);
        documentChunkRepository.saveEmbedding(redisChunk.getId(), unitVector(0), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "rebalance storms", 10, MODEL,
                new RetrievalFilter(Map.of("product", "kafka"), Map.of()));

        assertThat(candidates).extracting(HybridChunkMatch::documentTitle).containsOnly("Kafka Doc");
    }

    @Test
    void aQueryMatchingNothingLexicallyStillReturnsTheDenseCandidates() {
        Document document = documentRepository.save(new Document("Dense Only", null));
        DocumentChunk chunk = documentChunkRepository.save(
                new DocumentChunk(document, 0, "Restart the service and check the logs."));
        documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(0), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "zzzznonexistentterm", 10, MODEL, RetrievalFilter.none());

        HybridChunkMatch found = byChunkId(candidates, chunk.getId());
        assertThat(found.matchedDensely()).isTrue();
        assertThat(found.matchedLexically()).isFalse();
        assertThat(found.lexicalScore()).isEqualTo(0.0);
    }

    /**
     * Punctuation a user types must not reach the text-search parser as
     * operators. {@code plainto_tsquery} treats the whole string as words,
     * where {@code to_tsquery} would raise a syntax error and turn an ordinary
     * question into a 500.
     */
    @Test
    void punctuationInTheQueryDoesNotBreakTheTextSearch() {
        Document document = documentRepository.save(new Document("Punctuation", null));
        DocumentChunk chunk = documentChunkRepository.save(
                new DocumentChunk(document, 0, "Timeouts happen when the broker is slow."));
        documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(0), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "why & how <do> timeouts happen?!", 10, MODEL, RetrievalFilter.none());

        assertThat(candidates).isNotEmpty();
    }

    /**
     * Retrieve wide, rank narrow - but bounded. Each search contributes at most
     * the candidate limit, so the union is at most twice it.
     */
    @Test
    void eachSearchContributesAtMostTheCandidateLimit() {
        Document document = documentRepository.save(new Document("Many Chunks", null));
        for (int i = 0; i < 8; i++) {
            DocumentChunk chunk = documentChunkRepository.save(
                    new DocumentChunk(document, i, "kafka consumer rebalance number " + i));
            documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(0), MODEL);
        }

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(0), "kafka consumer rebalance", 3, MODEL, RetrievalFilter.none());

        assertThat(candidates).hasSizeLessThanOrEqualTo(6);
        assertThat(candidates).allSatisfy(candidate -> {
            if (candidate.denseRank() != null) {
                assertThat(candidate.denseRank()).isLessThanOrEqualTo(3);
            }
            if (candidate.lexicalRank() != null) {
                assertThat(candidate.lexicalRank()).isLessThanOrEqualTo(3);
            }
        });
    }

    /**
     * The generated tsvector column is maintained by Postgres, so it cannot
     * drift out of sync with the content it indexes - there is no code path in
     * the application that could update one without the other.
     */
    @Test
    void theTextSearchColumnIsPopulatedWithoutTheApplicationWritingIt() {
        Document document = documentRepository.save(new Document("Generated Column", null));
        DocumentChunk chunk = documentChunkRepository.save(
                new DocumentChunk(document, 0, "Idempotent producers deduplicate on retry."));
        documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(0), MODEL);

        List<HybridChunkMatch> candidates = documentChunkRepository.findHybridCandidates(
                unitVector(3), "idempotent producers", 10, MODEL, RetrievalFilter.none());

        assertThat(byChunkId(candidates, chunk.getId()).matchedLexically()).isTrue();
    }

    private static HybridChunkMatch byChunkId(List<HybridChunkMatch> candidates, Long chunkId) {
        return candidates.stream()
                .filter(candidate -> candidate.chunkId().equals(chunkId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("chunk " + chunkId + " was not among the candidates"));
    }

    private static float[] unitVector(int dimension) {
        float[] vector = new float[EMBEDDING_DIMENSIONS];
        vector[dimension] = 1.0f;
        return vector;
    }
}
