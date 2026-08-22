package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No customerId field, as of Phase 11 - the caller's identity comes from
 * Spring Security's authenticated principal (HTTP Basic auth), never from
 * anything the request body claims. See
 * {@link com.example.aiplatform.security.CurrentUser}.
 */
public record SupportAssistantRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 4000, message = "message must not exceed 4000 characters")
        String message
) {
}
