package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;

public record AskRequest(
        @NotBlank(message = "question must not be blank")
        String question
) {
}
