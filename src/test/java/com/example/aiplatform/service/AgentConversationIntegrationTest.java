package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.model.AgentResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The full Phase 10 scenario end to end: two real, separate calls to
 * {@link AgentService#handle}, with a real {@link org.springframework.ai.chat.memory.ChatMemory}
 * backed by real Redis in between - not a mocked ChatMemory. Only the two
 * points that would otherwise call a real provider (EmbeddingService,
 * LlmClientService) are mocked; conversation storage, retrieval, and the
 * history-splicing in AgentServiceImpl all run for real. This is the test
 * that actually proves "its" in turn two's question gets resolved using
 * information persisted from turn one, through real infrastructure.
 */
@Testcontainers
@SpringBootTest
class AgentConversationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private AgentService agentService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private LlmClientService llmClientService;

    @Test
    void secondTurnResolvesItsFromFirstTurnsHistoryViaRealRedis() {
        String customerId = "CUST-1001";
        String conversationId = "conv-multi-turn-" + System.nanoTime();

        // Both turns skip capability calls entirely (plan: no KB, no tool) -
        // memory threading is what's under test here, not planning quality
        // or tool execution, both already covered by Phases 8/9's own tests.
        when(llmClientService.generate(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            List<Message> instructions = prompt.getInstructions();
            String systemText = instructions.get(0).getText();
            String lastMessageText = instructions.get(instructions.size() - 1).getText();

            boolean isPlanningCall = systemText.contains("planning component");
            if (isPlanningCall) {
                return "{\"reasoning\":\"no lookup needed\",\"needsKnowledgeBase\":false,\"needsBusinessTool\":false}";
            }
            if (lastMessageText.contains("What is its payment status?")) {
                return "The payment is successful.";
            }
            return "I found order 12345.";
        });

        AgentResponse turnOne = agentService.handle(customerId, conversationId, "My order is 12345.");
        assertThat(turnOne.answer()).isEqualTo("I found order 12345.");

        AgentResponse turnTwo = agentService.handle(customerId, conversationId, "What is its payment status?");
        assertThat(turnTwo.answer()).isEqualTo("The payment is successful.");

        // The decisive assertion: turn two's FINALIZE call (identifiable by
        // its last message being turn two's own question) must have
        // received "12345" somewhere in its prompt - which is only possible
        // if turn one's messages were actually persisted to Redis, then
        // retrieved and spliced back in for turn two.
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmClientService, atLeastOnce()).generate(promptCaptor.capture());
        boolean turnTwoFinalizeSawOrderNumber = promptCaptor.getAllValues().stream()
                .filter(prompt -> lastMessageText(prompt).contains("What is its payment status?"))
                .anyMatch(prompt -> allMessageText(prompt).stream().anyMatch(text -> text.contains("12345")));
        assertThat(turnTwoFinalizeSawOrderNumber)
                .as("turn two's final prompt should contain '12345' via conversation history retrieved from Redis")
                .isTrue();
    }

    private static String lastMessageText(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        return instructions.get(instructions.size() - 1).getText();
    }

    private static List<String> allMessageText(Prompt prompt) {
        return prompt.getInstructions().stream().map(Message::getText).toList();
    }
}
