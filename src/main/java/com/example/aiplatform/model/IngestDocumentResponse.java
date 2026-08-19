package com.example.aiplatform.model;

public record IngestDocumentResponse(
        Long documentId,
        String title,
        int chunkCount
) {
}
