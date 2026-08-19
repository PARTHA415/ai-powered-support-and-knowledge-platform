package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.service.DocumentIngestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The repeatable RAG evaluation mechanism: loads a labeled dataset and a
 * fixture knowledge base from classpath JSON, ingests the fixture into a real
 * Postgres + pgvector container, runs every case through {@link RagEvaluator}
 * (real retrieval, real threshold filtering, real prompt construction), and
 * asserts against known-good and deliberately-known-bad cases.
 *
 * Two things are mocked, and only these two: {@link EmbeddingService} (would
 * otherwise call OpenAI) is replaced with a deterministic bag-of-words hash
 * embedding - similar text hashes to similar vectors, so retrieval behaves
 * meaningfully rather than randomly, without any network call or API cost.
 * {@link LlmClientService} returns fixed canned answers per question,
 * including at least one deliberately hallucinated citation and one
 * deliberately fabricated claim, specifically so this test can prove the
 * scorer actually catches them rather than trusting it blindly.
 *
 * IMPORTANT CAVEAT, and the reason this lives in a comment at the top of the
 * one test file everyone will read: the hash embedding is a crude,
 * purely-lexical stand-in for a real semantic embedding model. It cannot
 * bridge a vocabulary gap the way text-embedding-3-small can (see the
 * "paraphrased-password-question-lexical-gap" case below, which is expected
 * to rank an unrelated document above the truly relevant one for exactly
 * this reason - the relevant document still limps into the top-K, but only
 * on noise-level hash-collision similarity, not because the fake embedding
 * understood the paraphrase). A green run of this test demonstrates the
 * evaluation *harness* works correctly - it is not a substitute for
 * periodically running the same dataset against the real embedding model
 * and LLM to measure actual production RAG quality.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "app.rag.top-k=2",
        "app.rag.similarity-threshold=0.1"
})
@Transactional
class RagEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluationTest.class);
    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private RagEvaluator ragEvaluator;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private LlmClientService llmClientService;

    private static final AtomicBoolean FIXTURE_LOADED = new AtomicBoolean(false);

    private record FixtureDocument(String title, String source, String content) {
    }

    @BeforeAll
    static void logHarnessCaveat() {
        log.info("Running RAG evaluation with a deterministic hash-based fake embedding, NOT the real model. "
                + "See this test's class-level Javadoc before trusting these numbers as production RAG quality.");
    }

    private void ingestFixtureKnowledgeBaseOnce() throws IOException {
        if (!FIXTURE_LOADED.compareAndSet(false, true)) {
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        List<FixtureDocument> fixtureDocuments = objectMapper.readValue(
                new ClassPathResource("eval/knowledge-base-fixture.json").getInputStream(),
                new TypeReference<List<FixtureDocument>>() {
                });
        for (FixtureDocument document : fixtureDocuments) {
            documentIngestionService.ingest(document.title(), document.source(), document.content());
        }
    }

    private List<RagEvaluationCase> loadDataset() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(
                new ClassPathResource("eval/rag-evaluation-dataset.json").getInputStream(),
                new TypeReference<List<RagEvaluationCase>>() {
                });
    }

    @Test
    void evaluatesDatasetAndCatchesBothGoodAndBadCases() throws IOException {
        when(embeddingService.embed(anyString())).thenAnswer(invocation -> hashEmbedding(invocation.getArgument(0)));

        Map<String, String> cannedAnswers = new HashMap<>();
        cannedAnswers.put("How do I reset my password?",
                "To reset your password, open Settings, go to Security, and select Reset Password. [1]");
        // Deliberately bad: content is accurate, but the citation index is
        // hallucinated - no dataset ever has 9 sources.
        cannedAnswers.put("Why is my Kafka consumer falling behind?",
                "Check consumer lag, then restart the consumer group to clear the stuck offset. [9]");
        // Deliberately bad: "cryptocurrency" and "wire transfer" are not in
        // the retrieved context at all - a fabricated addition next to an
        // otherwise validly-cited claim.
        cannedAnswers.put("What payment methods do you accept?",
                "We accept credit card, PayPal, cryptocurrency, and wire transfer. [1]");
        cannedAnswers.put("I forgot my login credentials, what should I do?",
                "I don't have enough information to answer that.");
        cannedAnswers.put("How do I check the status of my order?",
                "To check your order status, open your order and select Track Package. [1]");
        cannedAnswers.put("What is the weather forecast for tomorrow?",
                "I don't have enough information to answer that.");

        when(llmClientService.generate(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String userText = prompt.getInstructions().get(1).getText();
            return cannedAnswers.entrySet().stream()
                    .filter(entry -> userText.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse("I don't have enough information to answer that.");
        });

        ingestFixtureKnowledgeBaseOnce();
        List<RagEvaluationCase> dataset = loadDataset();

        List<RagEvaluationResult> results = dataset.stream().map(ragEvaluator::evaluate).toList();
        RagEvaluationReport report = RagEvaluationReport.summarize(results);

        results.forEach(result -> log.info(
                "[{}] precision={} recall={} relevance={} groundedness={} citationsCorrect={} retrieved={} answer=\"{}\"",
                result.caseId(), result.retrievalMetrics().precision(), result.retrievalMetrics().recall(),
                result.relevanceScore(), result.groundednessScore(), result.citationsCorrect(),
                result.retrievedDocumentTitles(), result.answer()));
        log.info("Report: cases={} avgPrecision={} avgRecall={} avgRelevance={} avgGroundedness={} citationAccuracy={}",
                report.caseCount(), report.averagePrecision(), report.averageRecall(),
                report.averageRelevance(), report.averageGroundedness(), report.citationAccuracy());

        RagEvaluationResult passwordCase = findResult(results, "password-reset-direct-match");
        assertThat(passwordCase.retrievalMetrics().recall()).isEqualTo(1.0);
        assertThat(passwordCase.relevanceScore()).isEqualTo(1.0);
        assertThat(passwordCase.citationsCorrect()).isTrue();

        RagEvaluationResult kafkaCase = findResult(results, "kafka-consumer-lag");
        assertThat(kafkaCase.citationsCorrect())
                .as("citation [9] is deliberately out of range and must be caught as incorrect")
                .isFalse();

        RagEvaluationResult billingCase = findResult(results, "billing-payment-methods");
        assertThat(billingCase.citationsCorrect())
                .as("citation [1] is in range, so it's mechanically valid even though the claim is partly fabricated")
                .isTrue();
        assertThat(billingCase.groundednessScore())
                .as("fabricated payment methods not present in the source content should pull groundedness below 1.0")
                .isLessThan(1.0);

        RagEvaluationResult paraphraseCase = findResult(results, "paraphrased-password-question-lexical-gap");
        assertThat(paraphraseCase.retrievedDocumentTitles().get(0))
                .as("this is the deliberate bad-retrieval example: 'login credentials' shares essentially no "
                        + "real vocabulary with the password guide's text, so the purely-lexical hash embedding "
                        + "ranks an unrelated document ABOVE the truly relevant one (which only scrapes into the "
                        + "top-K on noise-level hash-collision similarity). A real semantic embedding model "
                        + "would not make this mistake - that gap is the whole point of this example.")
                .isNotEqualTo("Password Reset Guide");

        RagEvaluationResult outOfKbCase = findResult(results, "out-of-knowledge-base-question");
        assertThat(outOfKbCase.groundednessScore()).isEqualTo(1.0);
        assertThat(outOfKbCase.citationsCorrect()).isTrue();

        assertThat(report.citationAccuracy())
                .as("one deliberately bad case must pull citation accuracy below a perfect 1.0")
                .isLessThan(1.0);
        assertThat(report.averagePrecision())
                .as("topK=2 against mostly single-relevant-document cases structurally caps precision below 1.0")
                .isLessThan(1.0);
    }

    private static RagEvaluationResult findResult(List<RagEvaluationResult> results, String caseId) {
        return results.stream()
                .filter(result -> result.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No result for case " + caseId));
    }

    // Deliberately excluded from the hash embedding below: without this,
    // filler words ("is", "the", "for", "what"...) shared between totally
    // unrelated texts (e.g. "What is the weather forecast for tomorrow?" vs.
    // a Kafka troubleshooting doc that also contains "is"/"the"/"a") produce
    // spurious non-zero similarity - a real embedding model wouldn't make
    // this mistake because it represents meaning, not raw word identity, but
    // this crude stand-in has to be told explicitly to ignore function words.
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "to", "of", "in", "on", "for", "and", "or", "but",
            "do", "does", "did", "you", "your", "i", "my", "what", "how", "why", "when", "where", "who",
            "which", "this", "that", "these", "those", "will", "should", "can", "could", "would", "we",
            "if", "so", "then", "than", "with", "from", "by", "at", "as", "be", "been", "it", "its", "not", "no");

    /**
     * A deterministic bag-of-words embedding: each non-stopword token hashes
     * to one of 1536 dimensions and increments it, then the vector is left
     * unnormalized (pgvector's cosine-distance operator normalizes
     * internally). Text sharing more distinct content words ends up with
     * higher cosine similarity; text sharing none lands near-orthogonal. It
     * is a genuinely useful stand-in for testing pipeline wiring and
     * threshold/top-K behavior offline and for free - it is NOT a semantic
     * embedding, and cannot recognize synonyms or paraphrases the way
     * text-embedding-3-small does (see the deliberately-failing
     * "paraphrased-password-question-lexical-gap" case).
     */
    private static float[] hashEmbedding(String text) {
        float[] vector = new float[EMBEDDING_DIMENSIONS];
        for (String token : text.toLowerCase().split("\\W+")) {
            if (token.isBlank() || token.length() < 3 || STOPWORDS.contains(token)) {
                continue;
            }
            int index = Math.floorMod(token.hashCode(), vector.length);
            vector[index] += 1f;
        }
        return vector;
    }
}
