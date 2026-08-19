package com.example.aiplatform.controller;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.model.EmbedRequest;
import com.example.aiplatform.model.EmbedResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demonstration endpoint for Phase 4: turns text into an embedding vector.
 * No storage, no search yet - that starts in Phase 5 (pgvector) and Phase 6 (RAG).
 */
@RestController
@RequestMapping("/api/embeddings")
public class EmbeddingController {

    private final EmbeddingService embeddingService;
    private final String model;

    public EmbeddingController(EmbeddingService embeddingService,
                                @Value("${spring.ai.openai.embedding.options.model}") String model) {
        this.embeddingService = embeddingService;
        this.model = model;
    }

    @Operation(summary = "Generate an embedding vector for a piece of text")
    @PostMapping
    public ResponseEntity<EmbedResponse> embed(@Valid @RequestBody EmbedRequest request) {
        float[] vector = embeddingService.embed(request.text());
        return ResponseEntity.ok(new EmbedResponse(model, vector.length, vector));
    }
}
