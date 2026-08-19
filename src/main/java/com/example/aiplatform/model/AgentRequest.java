package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;

/**
 * customerId stands in for an authenticated principal until Phase 11 - see
 * {@link com.example.aiplatform.ai.tools.CallerContextHolder}, exactly as in
 * Phase 8's SupportAssistantRequest.
 */
public record AgentRequest(
        @NotBlank(message = "customerId must not be blank")
        String customerId,
        @NotBlank(message = "conversationId must not be blank")
        String conversationId,
        @NotBlank(message = "message must not be blank")
        String message
) {
}
