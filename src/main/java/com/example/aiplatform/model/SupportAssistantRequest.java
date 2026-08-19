package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;

/**
 * No customerId field, as of Phase 11 - the caller's identity comes from
 * Spring Security's authenticated principal (HTTP Basic auth), never from
 * anything the request body claims. See
 * {@link com.example.aiplatform.security.CurrentUser}.
 */
public record SupportAssistantRequest(
        @NotBlank(message = "message must not be blank")
        String message
) {
}
