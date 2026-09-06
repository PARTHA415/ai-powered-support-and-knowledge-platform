package com.example.aiplatform.model;

/**
 * @param replaced true when this ingest updated a document that already
 *                 existed at the same source rather than adding a new one.
 *                 Surfaced because "I posted a document and got a 200" reads
 *                 identically in both cases, and the difference matters: a
 *                 replace means the previous content and its embeddings are
 *                 gone.
 */
public record IngestDocumentResponse(
        Long documentId,
        String title,
        int chunkCount,
        boolean replaced
) {
}
