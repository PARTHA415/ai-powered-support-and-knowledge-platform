package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Conversation memory bounds (Phase 10). maxMessages is the sliding-window
 * size enforced by Spring AI's MessageWindowChatMemory - the concrete
 * defense against unbounded history being resent to the LLM on every turn.
 * ttlHours bounds how long a conversation survives in Redis before expiring
 * on its own, independent of the message-count window.
 */
@ConfigurationProperties(prefix = "app.memory")
public record MemoryProperties(
        @DefaultValue("20") int maxMessages,
        @DefaultValue("24") long ttlHours
) {
}
