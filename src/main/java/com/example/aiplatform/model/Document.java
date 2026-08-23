package com.example.aiplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 1000)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Free-form facets used for metadata-filtered retrieval - product,
     * component, version, audience, and whatever else a corpus needs.
     * Mapped as jsonb rather than a fixed set of columns because the useful
     * facets are not known up front and adding one should not require a
     * migration. Never null: an absent metadata map is an empty one, so
     * containment filtering has something well-defined to match against.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> metadata = new LinkedHashMap<>();

    protected Document() {
    }

    public Document(String title, String source) {
        this(title, source, Map.of());
    }

    public Document(String title, String source, Map<String, String> metadata) {
        this.title = title;
        this.source = source;
        this.createdAt = Instant.now();
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getSource() {
        return source;
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
