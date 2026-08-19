package com.example.aiplatform.repository;

import com.example.aiplatform.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentChunkRepository
        extends JpaRepository<DocumentChunk, Long>, DocumentChunkEmbeddingRepository {
}
