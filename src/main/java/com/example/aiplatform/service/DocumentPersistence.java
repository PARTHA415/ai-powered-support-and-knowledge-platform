package com.example.aiplatform.service;

import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.repository.DocumentChunkRepository;
import com.example.aiplatform.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private static final Logger log = LoggerFactory.getLogger(DocumentPersistence.class);

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
     *
     * <h2>Re-ingesting a source updates it rather than duplicating it</h2>
     *
     * A document is identified by its {@code source}, so posting the same
     * source twice replaces the first copy: same row, same id, new title,
     * metadata and chunks. Before this, every re-ingest added a copy, and
     * identical chunks then competed for the same top-k retrieval slots -
     * visible as retrieval precision collapsing while retrieval itself was
     * working perfectly.
     *
     * <p>A document ingested with a null source has no key to match on and is
     * always inserted; the unique index exempts nulls for the same reason.
     *
     * <p><b>Known window:</b> a replaced document's new chunks have no
     * embeddings until the batches in
     * {@link DocumentIngestionServiceImpl} complete, so for those few seconds
     * it is not retrievable - where previously it was. That is the cost of
     * re-chunking in place rather than building the replacement alongside the
     * original and swapping at the end. Acceptable for ingestion of a corpus
     * that is not being served under load; it would not be for a live index.
     */
    @Transactional
    public StoredDocument saveDocumentAndChunks(String title, String source, Map<String, String> metadata,
                                                 List<String> chunks) {
        Optional<Document> existing = source == null
                ? Optional.empty()
                : documentRepository.findBySource(source);

        Document document;
        if (existing.isPresent()) {
            document = existing.get();
            document.updateFrom(title, metadata);
            long removed = documentChunkRepository.deleteByDocumentId(document.getId());
            // Force the deletes out before the inserts. Hibernate orders
            // inserts ahead of deletes when flushing at commit, which is
            // harmless today only because nothing constrains
            // (document_id, chunk_index) - a constraint added later would turn
            // that into an intermittent failure with a baffling message.
            documentChunkRepository.flush();
            log.info("Re-ingesting source '{}' into existing document {}: replaced {} chunk(s) with {}",
                    source, document.getId(), removed, chunks.size());
        } else {
            document = documentRepository.save(new Document(title, source, metadata));
        }

        List<Long> chunkIds = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = documentChunkRepository.save(new DocumentChunk(document, i, chunks.get(i)));
            chunkIds.add(chunk.getId());
        }
        return new StoredDocument(document.getId(), document.getTitle(), List.copyOf(chunkIds), existing.isPresent());
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

    /**
     * The identifiers ingestion needs after the rows exist but before the
     * vectors do. {@code replaced} distinguishes an update to an existing
     * source from a first ingest - the caller cannot infer it from the id,
     * and silently updating a document someone believed they were adding is
     * exactly the kind of thing an operator should be told about.
     */
    public record StoredDocument(Long documentId, String title, List<Long> chunkIds, boolean replaced) {
    }
}
