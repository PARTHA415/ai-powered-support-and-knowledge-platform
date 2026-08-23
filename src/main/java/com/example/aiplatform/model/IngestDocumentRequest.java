package com.example.aiplatform.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record IngestDocumentRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 300, message = "title must not exceed 300 characters")
        String title,
        String source,
        @NotBlank(message = "content must not be blank")
        @Size(max = 50000, message = "content must not exceed 50000 characters")
        String content,
        /**
         * Optional facets stored with the document and usable as a retrieval
         * filter - e.g. {@code {"product":"kafka","audience":"internal"}}.
         * Capped because this lands in an indexed jsonb column: an unbounded
         * map from an HTTP caller is an unbounded index entry.
         */
        @Size(max = 20, message = "metadata must not contain more than 20 entries")
        Map<String, String> metadata
) {
    public Map<String, String> effectiveMetadata() {
        return metadata == null ? Map.of() : metadata;
    }
}
