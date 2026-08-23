package com.example.aiplatform.ai.embedding;

import com.example.aiplatform.exception.LlmIntegrationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Shares the {@code llm} circuit breaker with {@link com.example.aiplatform.ai.llm.SpringAiLlmClientService} -
 * embeddings and chat completions are typically the same provider/API key,
 * so an outage of one is a meaningful signal about the other; tracking them
 * as one failure domain rather than two independent breakers reflects that.
 *
 * <p>This class is the one place that knows the provider-specific embedding
 * model property. {@link #modelName()} publishes it through the portable
 * {@link EmbeddingService} seam so the ingestion and retrieval paths can record
 * and scope by it without importing an OpenAI-shaped configuration key.
 */
@Service
public class SpringAiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final String modelName;

    public SpringAiEmbeddingService(EmbeddingModel embeddingModel,
                                     @Value("${spring.ai.openai.embedding.options.model}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.modelName = modelName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    /**
     * {@code key} includes the model name, which is a correctness requirement
     * rather than cache hygiene. Keyed on text alone, changing
     * OPENAI_EMBEDDING_MODEL served vectors from the OLD model for up to the
     * cache TTL, quietly mixing two embedding spaces in one index. Including
     * the model means a model change simply misses the cache, as it should.
     */
    @Override
    @Cacheable(cacheNames = "embeddings", key = "#root.target.modelName() + '|' + #text")
    @CircuitBreaker(name = "llm", fallbackMethod = "embedFallback")
    public float[] embed(String text) {
        log.debug("Generating embedding ({} chars)", text.length());
        try {
            float[] vector = embeddingModel.embed(text);
            log.debug("Generated embedding ({} dimensions)", vector.length);
            return vector;
        } catch (Exception e) {
            throw asEmbeddingFailure(e);
        }
    }

    /**
     * Deliberately not {@code @Cacheable} - see {@link EmbeddingService#embedAll}.
     */
    @Override
    @CircuitBreaker(name = "llm", fallbackMethod = "embedAllFallback")
    public List<float[]> embedAll(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        log.debug("Generating {} embeddings in one batch call", texts.size());
        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            if (vectors.size() != texts.size()) {
                throw new LlmIntegrationException("Embedding provider returned " + vectors.size()
                        + " vectors for " + texts.size() + " inputs; refusing to store misaligned embeddings", null);
            }
            return vectors;
        } catch (Exception e) {
            throw asEmbeddingFailure(e);
        }
    }

    private float[] embedFallback(String text, Throwable cause) {
        throw circuitFallback(cause);
    }

    private List<float[]> embedAllFallback(List<String> texts, Throwable cause) {
        throw circuitFallback(cause);
    }

    /**
     * Resilience4j invokes a fallback for every exception leaving the
     * annotated method, not only for a call the breaker refused. Only
     * {@link CallNotPermittedException} means "the breaker is OPEN"; anything
     * else already has its own meaning and is propagated unchanged rather
     * than relabelled as a provider outage. Same reasoning as
     * {@code SpringAiLlmClientService.circuitOpenFallback}.
     */
    private RuntimeException circuitFallback(Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            log.error("Embedding circuit breaker is OPEN - failing fast while the provider recovers", cause);
            return new LlmIntegrationException(
                    "The AI service is temporarily unavailable and is being given time to recover "
                            + "(circuit breaker open) - please try again shortly.", cause);
        }
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new LlmIntegrationException("Failed to generate an embedding", cause);
    }

    private RuntimeException asEmbeddingFailure(Exception e) {
        if (e instanceof LlmIntegrationException llmIntegrationException) {
            return llmIntegrationException;
        }
        log.error("Embedding generation failed", e);
        return new LlmIntegrationException("Failed to generate an embedding", e);
    }
}
