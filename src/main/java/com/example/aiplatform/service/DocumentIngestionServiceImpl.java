package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.rag.TokenAwareChunker;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.IngestDocumentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates ingestion in three phases, and is deliberately NOT
 * {@code @Transactional} itself:
 *
 * <ol>
 *   <li>Split into chunks - pure computation, no I/O.</li>
 *   <li>Persist the document and its chunk rows - one short transaction
 *       ({@link DocumentPersistence}).</li>
 *   <li>Embed in batches and write the vectors back - the slow, billed network
 *       work happens between transactions, holding no database connection,
 *       with one short transaction per batch to store the results.</li>
 * </ol>
 *
 * <p>Chunk size and overlap are configurable and denominated in TOKENS
 * (app.rag.chunk-tokens / app.rag.chunk-overlap-tokens - see
 * {@link RagProperties}), and the splitting itself lives in
 * {@link TokenAwareChunker}: a real tokenizer, cutting at paragraph and
 * sentence boundaries rather than at an arbitrary character count. The
 * character-based splitter this replaced produced chunks whose true token size
 * varied by a factor of two with content density, which meant the most
 * information-dense chunks in a technical corpus were also the largest.
 */
@Service
public class DocumentIngestionServiceImpl implements DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);

    private final DocumentPersistence documentPersistence;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;
    private final TokenAwareChunker tokenAwareChunker;
    private final PromptInjectionGuard promptInjectionGuard;

    public DocumentIngestionServiceImpl(DocumentPersistence documentPersistence,
                                         EmbeddingService embeddingService,
                                         RagProperties ragProperties,
                                         TokenAwareChunker tokenAwareChunker,
                                         PromptInjectionGuard promptInjectionGuard) {
        this.documentPersistence = documentPersistence;
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
        this.tokenAwareChunker = tokenAwareChunker;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    @Override
    public IngestDocumentResponse ingest(String title, String source, String content,
                                          Map<String, String> metadata) {
        // Log-only, not blocking: ingestion is already staff-only RBAC, and a
        // document legitimately discussing injection techniques (a security
        // runbook) shouldn't be rejected outright. The primary indirect-
        // injection defense is the untrusted-content boundary applied where
        // retrieved text is spliced into a prompt - this is the earlier,
        // secondary signal: flag suspicious content at the source.
        if (promptInjectionGuard.containsInjectionAttempt(content)) {
            log.warn("Ingested document '{}' contains text resembling a prompt-injection attempt; "
                    + "it will still be stored, but retrieved content is fenced and sanitized before "
                    + "reaching any LLM prompt.", title);
        } else if (promptInjectionGuard.containsSuspiciousDatabaseLanguage(content)) {
            // Expected and fine in a technical runbook - recorded only so the
            // signal exists, never acted on. This text is no longer redacted
            // at retrieval time either: blanking SQL out of a SQL runbook
            // destroyed the usefulness of the document it was protecting.
            log.debug("Ingested document '{}' contains SQL-shaped text; storing and serving it unchanged", title);
        }

        List<String> chunks = tokenAwareChunker.split(content);

        DocumentPersistence.StoredDocument stored =
                documentPersistence.saveDocumentAndChunks(title, source, metadata, chunks);

        embedInBatches(stored.chunkIds(), chunks);

        log.info("Ingested document {} ('{}') as {} chunk(s) using embedding model {}",
                stored.documentId(), stored.title(), chunks.size(), embeddingService.modelName());
        return new IngestDocumentResponse(stored.documentId(), stored.title(), chunks.size());
    }

    /**
     * One provider call per batch instead of one per chunk. Batched rather than
     * sent as a single call for the whole document because providers cap both
     * request size and tokens per request; a fixed, configurable batch keeps
     * one oversized document from producing one oversized request.
     */
    private void embedInBatches(List<Long> chunkIds, List<String> chunks) {
        int batchSize = ragProperties.embeddingBatchSize();
        String modelName = embeddingService.modelName();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, chunks.size());
            List<String> batch = chunks.subList(start, end);
            List<Long> batchIds = chunkIds.subList(start, end);

            List<float[]> vectors = embeddingService.embedAll(batch);
            documentPersistence.saveEmbeddings(batchIds, vectors, modelName);

            log.debug("Embedded and stored chunks {}-{} of {}", start, end - 1, chunks.size());
        }
    }

}
