package com.example.aiplatform.ai.eval;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.service.DocumentIngestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The complete Phase 15 demonstration: all seven categories
 * {@link AiEvaluationService} covers - retrieval quality, relevance,
 * groundedness, citation correctness (delegated to {@link RagEvaluator},
 * Phase 7), plus answer correctness, hallucination, and safety behavior -
 * run together against the real, Spring-wired application and combined into
 * ONE {@link AiEvaluationReport}. (Tool selection and tool argument
 * correctness are the remaining two categories - see
 * {@code ToolSelectionEvaluationTest}, kept separate deliberately; its
 * Javadoc and the Phase 15 docs explain why.)
 *
 * Same two mocked boundaries as Phase 7's {@code RagEvaluationTest}, for the
 * same reason (a deterministic, repeatable CI check, not a live-LLM smoke
 * test): {@link EmbeddingService} is a hash embedding, {@link LlmClientService}
 * returns fixed canned answers keyed by question text - extended here to
 * also cover this phase's answer-correctness and hallucination questions,
 * which go through {@code SupportAssistantService} (tool-calling-capable)
 * rather than the plain RAG pipeline.
 */
@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "app.rag.top-k=2",
        "app.rag.similarity-threshold=0.1"
})
@Transactional
class FullAiEvaluationIT {

    private static final Logger log = LoggerFactory.getLogger(FullAiEvaluationIT.class);
    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private AiEvaluationService aiEvaluationService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private LlmClientService llmClientService;

    private record FixtureDocument(String title, String source, String content) {
    }

    private void ingestFixtureKnowledgeBase() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        List<FixtureDocument> fixtureDocuments = objectMapper.readValue(
                new ClassPathResource("eval/knowledge-base-fixture.json").getInputStream(),
                new TypeReference<List<FixtureDocument>>() {
                });
        for (FixtureDocument document : fixtureDocuments) {
            documentIngestionService.ingest(document.title(), document.source(), document.content(), java.util.Map.of());
        }
    }

    private static <T> List<T> loadClasspathDataset(String location, TypeReference<List<T>> typeReference) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(new ClassPathResource(location).getInputStream(), typeReference);
    }

    @Test
    void runsAllSevenNonToolCategoriesAndProducesOneCombinedReport() throws IOException {
        when(embeddingService.embed(anyString())).thenAnswer(invocation -> hashEmbedding(invocation.getArgument(0)));
        // Ingestion batches now, so embedAll - not embed - is what the pipeline
        // actually calls. Delegates to the same hash embedding so retrieval
        // behaves identically either way.
        when(embeddingService.embedAll(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(FullAiEvaluationIT::hashEmbedding).toList();
        });
        when(embeddingService.modelName()).thenReturn("test-hash-embedding");

        Map<String, String> cannedAnswers = new HashMap<>();
        // RAG dataset canned answers (Phase 7).
        cannedAnswers.put("How do I reset my password?",
                "To reset your password, open Settings, go to Security, and select Reset Password. [1]");
        cannedAnswers.put("Why is my Kafka consumer falling behind?",
                "Check consumer lag, then restart the consumer group to clear the stuck offset. [1]");
        cannedAnswers.put("What payment methods do you accept?",
                "We accept credit card and PayPal. [1]");
        cannedAnswers.put("I forgot my login credentials, what should I do?",
                "I don't have enough information to answer that.");
        cannedAnswers.put("How do I check the status of my order?",
                "To check your order status, open your order and select Track Package. [1]");
        cannedAnswers.put("What is the weather forecast for tomorrow?",
                "I don't have enough information to answer that.");
        // Answer-correctness dataset canned answers - correct, so this run
        // demonstrates a healthy pipeline; see the Phase 15 docs' exercises
        // for deliberately breaking one to watch the report catch it.
        cannedAnswers.put("What is the status of order ORD-1001?", "Your order ORD-1001 is SHIPPED.");
        cannedAnswers.put("What is the payment status for order ORD-1002?", "The payment for ORD-1002 is PENDING.");
        cannedAnswers.put("How many units of product PROD-2002 are in stock?", "PROD-2002 is out of stock.");
        cannedAnswers.put("What membership tier is customer CUST-1001?", "CUST-1001 is a GOLD tier member.");
        // Hallucination dataset canned answers - all honest declines.
        cannedAnswers.put("What is the CEO's personal phone number?", "I don't have enough information to answer that.");
        cannedAnswers.put("What is the status of order ORD-9999?",
                "I couldn't find an order with that ID, so I can't provide a status.");

        when(llmClientService.generate(any(Prompt.class))).thenAnswer(invocation ->
                cannedAnswer(cannedAnswers, invocation.getArgument(0)));
        when(llmClientService.generateWithTools(any(Prompt.class), any(com.example.aiplatform.ai.tools.SupportTools.class)))
                .thenAnswer(invocation -> cannedAnswer(cannedAnswers, invocation.getArgument(0)));

        ingestFixtureKnowledgeBase();

        List<RagEvaluationCase> ragCases = loadClasspathDataset("eval/rag-evaluation-dataset.json", new TypeReference<>() { });
        List<AnswerCorrectnessCase> answerCases = loadClasspathDataset(
                "eval/answer-correctness-dataset.json", new TypeReference<>() { });
        List<HallucinationCase> hallucinationCases = loadClasspathDataset(
                "eval/hallucination-dataset.json", new TypeReference<>() { });
        List<SafetyEvaluationCase> safetyCases = loadClasspathDataset(
                "eval/safety-evaluation-dataset.json", new TypeReference<>() { });

        List<EvaluationCaseResult> allResults = new ArrayList<>();
        allResults.addAll(aiEvaluationService.evaluateRag(ragCases));
        allResults.addAll(aiEvaluationService.evaluateAnswerCorrectness(answerCases));
        allResults.addAll(aiEvaluationService.evaluateHallucination(hallucinationCases));
        allResults.addAll(aiEvaluationService.evaluateSafety(safetyCases));

        AiEvaluationReport report = AiEvaluationReport.of(allResults);
        log.info("Full AI evaluation report:\n{}", report.toTable());

        assertThat(report.results()).hasSize(
                ragCases.size() * 4 + answerCases.size() + hallucinationCases.size() + safetyCases.size());
        assertThat(Set.of(EvaluationCategory.values())).containsAll(report.passRateByCategory().keySet());

        // Every category this run touches should have SOME passing cases -
        // proves the report isn't silently empty or universally failing.
        for (EvaluationCategory category : Set.of(EvaluationCategory.ANSWER_CORRECTNESS,
                EvaluationCategory.HALLUCINATION, EvaluationCategory.SAFETY_BEHAVIOR)) {
            assertThat(report.passRateByCategory().get(category))
                    .as("category %s should have a computed pass rate", category)
                    .isGreaterThan(0.0);
        }
    }

    private static String cannedAnswer(Map<String, String> cannedAnswers, Prompt prompt) {
        String userText = prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText();
        return cannedAnswers.entrySet().stream()
                .filter(entry -> userText.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("I don't have enough information to answer that.");
    }

    // Identical to Phase 7's RagEvaluationTest hash embedding - see its
    // Javadoc for the full explanation of what this stands in for and why.
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "to", "of", "in", "on", "for", "and", "or", "but",
            "do", "does", "did", "you", "your", "i", "my", "what", "how", "why", "when", "where", "who",
            "which", "this", "that", "these", "those", "will", "should", "can", "could", "would", "we",
            "if", "so", "then", "than", "with", "from", "by", "at", "as", "be", "been", "it", "its", "not", "no");

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
