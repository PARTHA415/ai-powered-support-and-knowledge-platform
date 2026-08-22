package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.model.IngestDocumentResponse;
import com.example.aiplatform.repository.DocumentChunkRepository;
import com.example.aiplatform.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunk size and overlap are configurable (app.rag.chunk-size /
 * app.rag.chunk-overlap - see {@link RagProperties}) rather than hardcoded,
 * so chunking strategy can be tuned without a code change. Splitting is still
 * a fixed-size, soft word-boundary strategy - no token-awareness or
 * semantic-boundary detection - but overlap now guards against a fact
 * landing right on a chunk boundary and becoming unretrievable on either
 * side of the cut.
 */
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;
    private final PromptInjectionGuard promptInjectionGuard;

    public DocumentIngestionServiceImpl(DocumentRepository documentRepository,
                                         DocumentChunkRepository documentChunkRepository,
                                         EmbeddingService embeddingService,
                                         RagProperties ragProperties,
                                         PromptInjectionGuard promptInjectionGuard) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    @Override
    @Transactional
    public IngestDocumentResponse ingest(String title, String source, String content) {
        // Log-only, not blocking: ingestion is already staff-only (Phase 11
        // RBAC), and a document legitimately discussing injection techniques
        // (e.g. a security runbook) shouldn't be rejected outright. The
        // primary indirect-injection defense is sanitizing at the point
        // content is actually spliced into a prompt (see
        // QuestionAnsweringServiceImpl.buildContext / AgentServiceImpl's
        // formatKnowledgeBaseEvidence) - this is the earlier, secondary
        // signal: flag suspicious content at the source, for visibility.
        if (promptInjectionGuard.containsInjectionAttempt(content)) {
            log.warn("Ingested document '{}' contains text resembling a prompt-injection attempt; "
                    + "it will still be stored, but is sanitized at retrieval time before being "
                    + "spliced into any LLM prompt.", title);
        }

        Document document = documentRepository.save(new Document(title, source));

        List<String> chunks = splitIntoChunks(content, ragProperties.chunkSize(), ragProperties.chunkOverlap());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = documentChunkRepository.save(new DocumentChunk(document, i, chunks.get(i)));
            float[] embedding = embeddingService.embed(chunks.get(i));
            documentChunkRepository.saveEmbedding(chunk.getId(), embedding);
        }

        return new IngestDocumentResponse(document.getId(), document.getTitle(), chunks.size());
    }

    private static List<String> splitIntoChunks(String text, int chunkSize, int chunkOverlap) {
        int overlap = Math.max(0, Math.min(chunkOverlap, chunkSize - 1));
        List<String> chunks = new ArrayList<>();
        String trimmed = text.strip();
        int start = 0;
        while (start < trimmed.length()) {
            int end = Math.min(start + chunkSize, trimmed.length());
            if (end < trimmed.length()) {
                int lastSpace = trimmed.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            chunks.add(trimmed.substring(start, end).strip());
            if (end >= trimmed.length()) {
                break;
            }
            // Step back by the overlap amount so the next chunk re-includes
            // the trailing text of this one, instead of always advancing to
            // exactly where this chunk ended.
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }
}
