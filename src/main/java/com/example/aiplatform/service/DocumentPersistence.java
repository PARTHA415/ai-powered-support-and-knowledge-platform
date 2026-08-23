package com.example.aiplatform.service;

import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.repository.DocumentChunkRepository;
import com.example.aiplatform.repository.DocumentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The transactional half of ingestion, deliberately a separate bean from
 * {@link DocumentIngestionServiceImpl}.
 *
 * <p>Ingestion previously ran embedding calls inside {@code @Transactional}:
 * one sequential, billed HTTP round trip per chunk while holding a pooled
 * database connection and a servlet thread. A 100&nbsp;KB document is roughly
 * 130 chunks, so a handful of concurrent uploads could exhaust the connection
 * pool on network wait alone - connections held open for minutes doing nothing
 * but waiting on a socket.
 *
 * <p>Splitting the persistence steps into their own bean is what makes the fix
 * possible at all: {@code @Transactional} is applied by a Spring AOP proxy, and
 * a private helper called from within the same class bypasses the proxy
 * entirely, so the annotation would have been silently inert. Two beans means
 * two short transactions - write the rows, then later write the vectors - with
 * the slow network work happening between them, holding no connection.
 */
@Component
public class DocumentPersistence {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentPersistence(DocumentRepository documentRepository,
                                DocumentChunkRepository documentChunkRepository) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    /**
     * Transaction one: the document row and all of its chunk rows, with no
     * embeddings yet. Chunks are persisted before their vectors exist, which
     * is why retrieval requires a non-null embedding.
     */
    @Transactional
    public StoredDocument saveDocumentAndChunks(String title, String source, Map<String, String> metadata,
                                                 List<String> chunks) {
        Document document = documentRepository.save(new Document(title, source, metadata));
        List<Long> chunkIds = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = documentChunkRepository.save(new DocumentChunk(document, i, chunks.get(i)));
            chunkIds.add(chunk.getId());
        }
        return new StoredDocument(document.getId(), document.getTitle(), List.copyOf(chunkIds));
    }

    /**
     * Transaction two (one per batch): attach the vectors produced outside any
     * transaction. Per-batch rather than one transaction for the whole
     * document so a large ingest does not hold a single long-running write
     * transaction open across every batch.
     */
    @Transactional
    public void saveEmbeddings(List<Long> chunkIds, List<float[]> embeddings, String embeddingModel) {
        // Positional pairing: embeddings[i] belongs to chunkIds[i]. If the two
        // lists ever disagree, indexing blindly either throws
        // IndexOutOfBoundsException from deep inside a loop - which says
        // nothing about what went wrong - or, worse, silently stores vectors
        // against the wrong chunks and corrupts retrieval with no error at all.
        // Checked here rather than trusted from the caller, because this is a
        // public method on a Spring bean and the pairing is the one invariant
        // it cannot verify after the fact.
        if (chunkIds.size() != embeddings.size()) {
            throw new IllegalArgumentException("Refusing to store embeddings: got " + embeddings.size()
                    + " vectors for " + chunkIds.size() + " chunks. These are paired positionally, so a "
                    + "mismatch would attach vectors to the wrong chunks.");
        }
        for (int i = 0; i < chunkIds.size(); i++) {
            documentChunkRepository.saveEmbedding(chunkIds.get(i), embeddings.get(i), embeddingModel);
        }
    }

    /** The identifiers ingestion needs after the rows exist but before the vectors do. */
    public record StoredDocument(Long documentId, String title, List<Long> chunkIds) {
    }
}
