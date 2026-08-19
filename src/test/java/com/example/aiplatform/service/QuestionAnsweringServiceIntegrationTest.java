package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.model.AskResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end test of the full Phase 6 pipeline - ingest -> chunk -> embed ->
 * store -> retrieve -> filter by similarity threshold -> build context ->
 * prompt -> "generate" - against a real Postgres + pgvector container.
 *
 * The only things mocked out are the two points that would otherwise call a
 * real LLM provider (EmbeddingService, LlmClientService); everything in
 * between - chunking, pgvector storage, the cosine-distance nearest-neighbor
 * query, threshold filtering, and context/prompt construction - runs for
 * real, which is exactly the part unit tests with mocked repositories can't
 * verify.
 */
@Testcontainers
@SpringBootTest
@Transactional
class QuestionAnsweringServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private QuestionAnsweringService questionAnsweringService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private LlmClientService llmClientService;

    @Test
    void endToEndPipelineRetrievesOnlyTheRelevantDocumentAndCitesIt() {
        // "Directions" in embedding space: the password doc and the matching
        // question point the same way; the Kafka doc points orthogonally, so
        // its similarity to the question (0.0) falls below the configured
        // 0.5 threshold and must be excluded from both the prompt context
        // and the response's source list.
        when(embeddingService.embed(contains("password"))).thenReturn(unitVector(0));
        when(embeddingService.embed(contains("Kafka"))).thenReturn(unitVector(1));
        when(llmClientService.generate(any(Prompt.class)))
                .thenReturn("Go to Settings > Security > Reset Password. [1]");

        documentIngestionService.ingest("Password Reset Guide", "kb/password.md",
                "To reset your password, go to Settings > Security > Reset Password.");
        documentIngestionService.ingest("Kafka Troubleshooting", "kb/kafka.md",
                "Restart the Kafka consumer group to fix consumer failures.");

        AskResponse response = questionAnsweringService.answer("How do I reset my password?");

        assertThat(response.answer()).isEqualTo("Go to Settings > Security > Reset Password. [1]");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).documentTitle()).isEqualTo("Password Reset Guide");

        // The prompt actually sent to the LLM must be grounded in the
        // retrieved chunk, and must not leak the filtered-out Kafka chunk.
        verify(llmClientService).generate(argThat(prompt -> {
            String userMessage = prompt.getInstructions().get(1).getText();
            return userMessage.contains("[1] (Password Reset Guide)")
                    && !userMessage.contains("Kafka");
        }));
    }

    private static float[] unitVector(int dimension) {
        float[] vector = new float[EMBEDDING_DIMENSIONS];
        vector[dimension] = 1.0f;
        return vector;
    }
}
