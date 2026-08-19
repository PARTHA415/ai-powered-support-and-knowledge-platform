package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;

/**
 * customerId stands in for an authenticated principal until Phase 11 wires
 * up real Spring Security - see {@link com.example.aiplatform.ai.tools.CallerContextHolder}.
 * It is deliberately part of the trusted request context, not something the
 * LLM ever supplies or influences.
 */
public record SupportAssistantRequest(
        @NotBlank(message = "customerId must not be blank")
        String customerId,
        @NotBlank(message = "message must not be blank")
        String message
) {
}
