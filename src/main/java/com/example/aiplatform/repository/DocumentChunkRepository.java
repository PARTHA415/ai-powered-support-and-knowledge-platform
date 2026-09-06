package com.example.aiplatform.repository;

import com.example.aiplatform.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentChunkRepository
        extends JpaRepository<DocumentChunk, Long>, DocumentChunkEmbeddingRepository {

    /**
     * Clears a document's chunks so it can be re-chunked from new content.
     * Returns the number removed, which is worth logging: a replacement that
     * silently deleted nothing means the lookup found the wrong document.
     */
    long deleteByDocumentId(Long documentId);
}
