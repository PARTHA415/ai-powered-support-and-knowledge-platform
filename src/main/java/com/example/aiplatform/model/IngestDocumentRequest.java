package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;

public record IngestDocumentRequest(
        @NotBlank(message = "title must not be blank")
        String title,
        String source,
        @NotBlank(message = "content must not be blank")
        String content
) {
}
