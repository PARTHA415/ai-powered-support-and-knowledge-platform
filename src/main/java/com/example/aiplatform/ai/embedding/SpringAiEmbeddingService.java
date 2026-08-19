package com.example.aiplatform.ai.embedding;

import com.example.aiplatform.exception.LlmIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class SpringAiEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
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
}
