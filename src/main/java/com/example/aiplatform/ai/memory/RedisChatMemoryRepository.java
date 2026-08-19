package com.example.aiplatform.ai.memory;

import com.example.aiplatform.config.MemoryProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The actual persistence mechanics behind conversation memory: each
 * conversation's message list is serialized to a small JSON array and stored
 * under one Redis string key, with a TTL so abandoned conversations expire
 * on their own instead of accumulating forever. This is intentionally a
 * hand-written {@link ChatMemoryRepository} implementation rather than a
 * pre-built Spring AI Redis starter - the point of this phase is to make
 * "where does conversation history actually live, and how" visible, not
 * hidden behind another framework dependency.
 *
 * Messages are stored as {role, content} pairs rather than relying on
 * Jackson's polymorphic (de)serialization of the {@link Message} interface -
 * simpler, and avoids needing a Jackson module or mixin just to round-trip
 * four known message types.
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat:memory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      MemoryProperties memoryProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofHours(memoryProperties.ttlHours());
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null) {
            return List.of();
        }
        return keys.stream().map(key -> key.substring(KEY_PREFIX.length())).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return deserialize(json);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        redisTemplate.opsForValue().set(key(conversationId), serialize(messages), ttl);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private String serialize(List<Message> messages) {
        List<StoredMessage> stored = messages.stream()
                .map(message -> new StoredMessage(message.getMessageType().name(), message.getText()))
                .toList();
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize conversation messages", e);
        }
    }

    private List<Message> deserialize(String json) {
        try {
            List<StoredMessage> stored = objectMapper.readValue(json, new TypeReference<List<StoredMessage>>() {
            });
            return stored.stream().map(RedisChatMemoryRepository::toMessage).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize conversation messages", e);
        }
    }

    private static Message toMessage(StoredMessage stored) {
        MessageType type = MessageType.valueOf(stored.role());
        return switch (type) {
            case ASSISTANT -> new AssistantMessage(stored.content());
            case SYSTEM -> new SystemMessage(stored.content());
            default -> new UserMessage(stored.content());
        };
    }

    private record StoredMessage(String role, String content) {
    }
}
