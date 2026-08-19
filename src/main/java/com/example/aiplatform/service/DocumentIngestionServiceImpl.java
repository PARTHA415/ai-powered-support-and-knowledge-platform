package com.example.aiplatform.service;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.model.IngestDocumentResponse;
import com.example.aiplatform.repository.DocumentChunkRepository;
import com.example.aiplatform.repository.DocumentRepository;
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

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;
    private final RagProperties ragProperties;

    public DocumentIngestionServiceImpl(DocumentRepository documentRepository,
                                         DocumentChunkRepository documentChunkRepository,
                                         EmbeddingService embeddingService,
                                         RagProperties ragProperties) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
    }

    @Override
    @Transactional
    public IngestDocumentResponse ingest(String title, String source, String content) {
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
