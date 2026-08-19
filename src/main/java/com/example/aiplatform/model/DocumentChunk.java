package com.example.aiplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The embedding column (document_chunk.embedding, a pgvector "vector(1536)")
 * is deliberately NOT mapped as a JPA field here - see the repository layer
 * (DocumentChunkEmbeddingRepositoryImpl), which writes/reads it via plain SQL
 * instead of Hibernate's native vector type. Reasoning is in the Phase 5 docs.
 */
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    protected DocumentChunk() {
    }

    public DocumentChunk(Document document, int chunkIndex, String content) {
        this.document = document;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }
}
