package com.example.aiplatform.ai.memory;

import com.example.aiplatform.config.MemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual persistence mechanics - real Redis, real
 * serialization round-trip - directly, without a full Spring context (no
 * Postgres/datasource needed just to prove Redis storage is correct).
 */
@Testcontainers
class RedisChatMemoryRepositoryIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        repository = new RedisChatMemoryRepository(redisTemplate, new ObjectMapper(), new MemoryProperties(20, 24));
    }

    @Test
    void saveAllThenFindByConversationIdRoundTripsMessagesInOrderAndType() {
        String conversationId = conversationId();
        List<Message> messages = List.of(
                new UserMessage("My order is 12345."),
                new AssistantMessage("I found order 12345."));

        repository.saveAll(conversationId, messages);
        List<Message> found = repository.findByConversationId(conversationId);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(found.get(0).getText()).isEqualTo("My order is 12345.");
        assertThat(found.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(found.get(1).getText()).isEqualTo("I found order 12345.");
    }

    @Test
    void findByConversationIdReturnsEmptyListForAnUnknownConversation() {
        assertThat(repository.findByConversationId("does-not-exist")).isEmpty();
    }

    @Test
    void saveAllOverwritesRatherThanAppendsForTheSameConversation() {
        String conversationId = conversationId();
        repository.saveAll(conversationId, List.of(new UserMessage("first")));

        repository.saveAll(conversationId, List.of(
                new UserMessage("first"), new AssistantMessage("reply"), new UserMessage("second")));

        assertThat(repository.findByConversationId(conversationId)).hasSize(3);
    }

    @Test
    void deleteByConversationIdRemovesStoredMessages() {
        String conversationId = conversationId();
        repository.saveAll(conversationId, List.of(new UserMessage("hello")));

        repository.deleteByConversationId(conversationId);

        assertThat(repository.findByConversationId(conversationId)).isEmpty();
    }

    @Test
    void findConversationIdsListsEveryStoredConversation() {
        String conversationId = conversationId();
        repository.saveAll(conversationId, List.of(new UserMessage("hello")));

        assertThat(repository.findConversationIds()).contains(conversationId);
    }

    private static String conversationId() {
        return "conv-" + UUID.randomUUID();
    }
}
