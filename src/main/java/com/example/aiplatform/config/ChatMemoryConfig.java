package com.example.aiplatform.config;

import com.example.aiplatform.ai.memory.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps the Redis-backed storage layer with Spring AI's MessageWindowChatMemory,
 * which enforces the sliding-window bound (app.memory.max-messages) every
 * time a turn is added - this is where "prevent unbounded conversation
 * history" is actually enforced, reusing a tested framework class rather
 * than hand-rolling truncation logic.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisChatMemoryRepository, MemoryProperties memoryProperties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(redisChatMemoryRepository)
                .maxMessages(memoryProperties.maxMessages())
                .build();
    }
}
