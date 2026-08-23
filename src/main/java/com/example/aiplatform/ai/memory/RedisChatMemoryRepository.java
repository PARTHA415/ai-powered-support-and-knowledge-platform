package com.example.aiplatform.ai.memory;

import com.example.aiplatform.config.MemoryProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversation storage, backed by one Redis LIST per conversation.
 *
 * <p>This was previously one JSON blob per conversation under a string key, and
 * that shape forced two problems.
 *
 * <p><b>Lost turns.</b> Appending meant read the whole blob, add to it, write it
 * back. Two concurrent turns in the same conversation - two browser tabs, a
 * retry, a double-submit - both read the same starting state and the later
 * write silently discarded the earlier turn. {@link #appendAll} now does the
 * append, the window trim, and the TTL refresh in a single Lua script, which
 * Redis executes atomically: concurrent turns serialize instead of racing, and
 * no read-modify-write window exists to lose anything in.
 *
 * <p><b>A blocking KEYS scan.</b> {@link #findConversationIds()} used
 * {@code KEYS}, which walks every key in the instance and blocks Redis
 * single-threaded for the duration - on a large keyspace that is a
 * self-inflicted outage for every other consumer. It now uses {@code SCAN},
 * which returns in bounded chunks and lets other commands interleave.
 *
 * <p>Messages are stored as {role, content} pairs rather than relying on
 * Jackson's polymorphic (de)serialization of the {@link Message} interface -
 * simpler, and avoids needing a Jackson module or mixin just to round-trip
 * four known message types.
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final int SCAN_BATCH_SIZE = 500;

    /**
     * RPUSH every new message, LTRIM to the newest maxMessages, then refresh
     * the TTL - as one atomic unit.
     *
     * <p>KEYS[1] is the conversation key; ARGV[1] is the window size, ARGV[2]
     * the TTL in seconds, and ARGV[3..] the serialized messages. The trim keeps
     * the TAIL (-N..-1) because the newest turns are the ones worth keeping
     * when the window overflows.
     */
    private static final RedisScript<Void> APPEND_SCRIPT = new DefaultRedisScript<>(
            """
            for i = 3, #ARGV do
              redis.call('RPUSH', KEYS[1], ARGV[i])
            end
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[1]), -1)
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return nil
            """, Void.class);

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

    /**
     * Atomically appends messages and enforces the sliding window.
     *
     * <p>The window bound is applied here, in the same atomic operation as the
     * append, rather than by a wrapper that reads, truncates in Java, and
     * writes back - which is precisely the read-modify-write this class exists
     * to avoid.
     */
    public void appendAll(String conversationId, List<Message> messages, int maxMessages) {
        if (messages.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(messages.size() + 2);
        args.add(Integer.toString(maxMessages));
        args.add(Long.toString(ttl.toSeconds()));
        for (Message message : messages) {
            args.add(serialize(message));
        }
        redisTemplate.execute(APPEND_SCRIPT, List.of(key(conversationId)), args.toArray());
    }

    @Override
    public List<String> findConversationIds() {
        List<String> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(SCAN_BATCH_SIZE)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                ids.add(cursor.next().substring(KEY_PREFIX.length()));
            }
        }
        return ids;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<String> stored = redisTemplate.opsForList().range(key(conversationId), 0, -1);
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        return stored.stream().map(this::deserialize).toList();
    }

    /**
     * Replaces the conversation wholesale. Part of Spring AI's
     * {@link ChatMemoryRepository} contract and kept for it, but note that
     * {@link com.example.aiplatform.ai.memory.RedisWindowChatMemory} appends via
     * {@link #appendAll} instead - replace-the-whole-list is exactly the
     * non-atomic shape that lost turns.
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        String key = key(conversationId);
        redisTemplate.delete(key);
        if (messages.isEmpty()) {
            return;
        }
        redisTemplate.opsForList().rightPushAll(key, messages.stream().map(this::serialize).toList());
        redisTemplate.expire(key, ttl);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private String serialize(Message message) {
        try {
            return objectMapper.writeValueAsString(
                    new StoredMessage(message.getMessageType().name(), message.getText()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize conversation message", e);
        }
    }

    private Message deserialize(String json) {
        try {
            return toMessage(objectMapper.readValue(json, StoredMessage.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize conversation message", e);
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
