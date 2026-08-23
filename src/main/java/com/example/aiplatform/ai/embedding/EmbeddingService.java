package com.example.aiplatform.ai.embedding;

import java.util.List;

/**
 * Thin seam over whichever embedding provider is wired up - the embedding
 * counterpart to {@link com.example.aiplatform.ai.llm.LlmClientService}.
 * Keeps callers from depending on a specific provider's SDK directly, so the
 * embedding model/provider can change without touching business logic.
 */
public interface EmbeddingService {

    /**
     * Embeds one text. Cached, because a repeated query - the same support
     * question asked twice, a knowledge-base search re-run - is common and the
     * call is billed.
     */
    float[] embed(String text);

    /**
     * Embeds many texts in a single provider call, returning vectors in the
     * same order.
     *
     * <p>Separate from {@link #embed(String)} rather than a loop over it, for
     * two reasons. Ingestion previously made one sequential HTTP round trip per
     * chunk - roughly 130 for a 100&nbsp;KB document - when the provider
     * accepts batches; that is the difference between one call and a hundred.
     * And this path is deliberately NOT cached: chunk text is embedded once and
     * essentially never re-embedded, so caching it only evicts the query
     * embeddings the cache exists to serve.
     */
    List<float[]> embedAll(List<String> texts);

    /**
     * Identifies the model that produced the vectors this service returns.
     *
     * <p>Exposed on the interface so callers can record it with each stored
     * vector and scope retrieval to it, without any of them having to know a
     * provider-specific configuration key. The concrete adapter is the only
     * place that knows where the name comes from.
     */
    String modelName();
}
