package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskRequest(
        @NotBlank(message = "question must not be blank")
        @Size(max = 2000, message = "question must not exceed 2000 characters")
        String question
) {
}
