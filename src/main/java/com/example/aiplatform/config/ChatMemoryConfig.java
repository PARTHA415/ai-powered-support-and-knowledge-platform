package com.example.aiplatform.config;

import com.example.aiplatform.ai.memory.OwnerScopedChatMemory;
import com.example.aiplatform.ai.memory.RedisChatMemoryRepository;
import com.example.aiplatform.ai.memory.RedisWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles conversation memory as three layers, innermost first:
 *
 * <ol>
 *   <li>{@link RedisChatMemoryRepository} - where the messages actually
 *       live, one Redis LIST per key, with a TTL.</li>
 *   <li>{@link RedisWindowChatMemory} - applies the sliding-window bound
 *       (app.memory.max-messages) as part of an atomic append. This replaces
 *       Spring AI's {@code MessageWindowChatMemory}, whose read-modify-write
 *       {@code add} silently dropped a turn when two arrived concurrently for
 *       the same conversation.</li>
 *   <li>{@link OwnerScopedChatMemory} - binds the conversation key to the
 *       authenticated caller, so one user cannot read or append to another
 *       user's conversation by naming its ID.</li>
 * </ol>
 *
 * <p>Ownership scoping is the OUTERMOST layer deliberately. Every key that
 * reaches the window and the repository is already caller-scoped, so neither
 * of those layers has to know anything about identity, and there is no path
 * into storage that skips the check.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository, MemoryProperties memoryProperties) {
        ChatMemory windowed = new RedisWindowChatMemory(redisChatMemoryRepository, memoryProperties.maxMessages());
        return new OwnerScopedChatMemory(windowed);
    }
}
