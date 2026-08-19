package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;

public record EmbedRequest(
        @NotBlank(message = "text must not be blank")
        String text
) {
}
