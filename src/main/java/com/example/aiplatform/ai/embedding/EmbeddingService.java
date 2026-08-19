package com.example.aiplatform.ai.embedding;

/**
 * Thin seam over whichever embedding provider is wired up - the embedding
 * counterpart to {@link com.example.aiplatform.ai.llm.LlmClientService}.
 * Keeps callers from depending on a specific provider's SDK directly, so the
 * embedding model/provider can change without touching business logic.
 */
public interface EmbeddingService {

    float[] embed(String text);
}
