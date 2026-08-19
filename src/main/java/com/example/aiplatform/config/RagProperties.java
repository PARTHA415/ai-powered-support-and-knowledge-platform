package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable knobs for the RAG pipeline (Phase 6): how documents get split into
 * chunks at ingestion time, and how many chunks get retrieved/considered
 * relevant at question-answering time. Deliberately global config rather than
 * per-request parameters - these are pipeline-tuning concerns, not something
 * a caller of /api/qa should be adjusting per call.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        @DefaultValue("800") int chunkSize,
        @DefaultValue("100") int chunkOverlap,
        @DefaultValue("5") int topK,
        @DefaultValue("0.5") double similarityThreshold
) {
}
