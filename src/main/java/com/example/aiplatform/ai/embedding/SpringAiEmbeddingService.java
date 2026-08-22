package com.example.aiplatform.ai.embedding;

import com.example.aiplatform.exception.LlmIntegrationException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Phase 16: shares the {@code llm} circuit breaker with {@link com.example.aiplatform.ai.llm.SpringAiLlmClientService} -
 * embeddings and chat completions are typically the same provider/API key,
 * so an outage of one is a meaningful signal about the other; tracking them
 * as one failure domain rather than two independent breakers reflects that.
 *
 * {@code @Cacheable}: identical text embeds to the identical vector for a
 * fixed model version, and a repeat query (the same support question asked
 * twice, a knowledge-base search re-run) is common enough that skipping a
 * real, billed OpenAI call for it is a meaningful cost/latency win with no
 * correctness downside - see application.yml's {@code spring.cache.caffeine.spec}
 * for the bounded size + TTL this relies on.
 */
@Service
public class SpringAiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    @Cacheable("embeddings")
    @CircuitBreaker(name = "llm", fallbackMethod = "embedFallback")
    public float[] embed(String text) {
        log.debug("Generating embedding ({} chars)", text.length());
        try {
            float[] vector = embeddingModel.embed(text);
            log.debug("Generated embedding ({} dimensions)", vector.length);
            return vector;
        } catch (Exception e) {
            log.error("Embedding generation failed", e);
            throw new LlmIntegrationException("Failed to generate an embedding", e);
        }
    }

    private float[] embedFallback(String text, Throwable cause) {
        log.error("Embedding circuit breaker fallback triggered - the provider appears to be down", cause);
        throw new LlmIntegrationException(
                "The AI service is temporarily unavailable and is being given time to recover "
                        + "(circuit breaker open) - please try again shortly.", cause);
    }
}
