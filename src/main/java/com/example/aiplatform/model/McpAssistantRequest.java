package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No customerId field, same reasoning as {@link SupportAssistantRequest}:
 * the caller's identity comes from Spring Security's authenticated
 * principal, forwarded to the MCP connection by
 * {@code com.example.aiplatform.controller.McpDemoController} - never from
 * anything the request body claims.
 */
public record McpAssistantRequest(
        @NotBlank(message = "message must not be blank")
        @Size(max = 4000, message = "message must not exceed 4000 characters")
        String message
) {
}
