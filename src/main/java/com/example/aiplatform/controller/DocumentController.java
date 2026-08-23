package com.example.aiplatform.controller;

import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.model.IngestDocumentRequest;
import com.example.aiplatform.model.IngestDocumentResponse;
import com.example.aiplatform.model.SemanticSearchRequest;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.service.DocumentIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;
    private final SemanticSearchService semanticSearchService;

    public DocumentController(DocumentIngestionService documentIngestionService,
                               SemanticSearchService semanticSearchService) {
        this.documentIngestionService = documentIngestionService;
        this.semanticSearchService = semanticSearchService;
    }

    @Operation(summary = "Ingest a document: split into chunks, embed each chunk, and store it")
    @PostMapping
    public ResponseEntity<IngestDocumentResponse> ingest(@Valid @RequestBody IngestDocumentRequest request) {
        return ResponseEntity.ok(
                documentIngestionService.ingest(request.title(), request.source(), request.content(),
                        request.effectiveMetadata()));
    }

    @Operation(summary = "Semantic search over ingested document chunks (no LLM answer synthesis - that's Phase 6)")
    @PostMapping("/search")
    public ResponseEntity<List<SemanticSearchResult>> search(@Valid @RequestBody SemanticSearchRequest request) {
        return ResponseEntity.ok(semanticSearchService.search(request.query(), request.effectiveLimit(),
                request.effectiveMetadataFilter()));
    }
}
