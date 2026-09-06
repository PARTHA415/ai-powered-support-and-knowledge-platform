package com.example.aiplatform.repository;

import com.example.aiplatform.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * Looks a document up by its natural key so that re-ingesting the same
     * source replaces it instead of adding a copy - see
     * {@code V5__unique_document_source.sql}. Unique in the database, hence
     * Optional rather than a list.
     */
    Optional<Document> findBySource(String source);
}
