package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IngestDocumentRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 300, message = "title must not exceed 300 characters")
        String title,
        String source,
        @NotBlank(message = "content must not be blank")
        @Size(max = 50000, message = "content must not exceed 50000 characters")
        String content
) {
}
