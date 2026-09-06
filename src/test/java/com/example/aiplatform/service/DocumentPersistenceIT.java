package com.example.aiplatform.service;

import com.example.aiplatform.model.Document;
import com.example.aiplatform.repository.DocumentChunkRepository;
import com.example.aiplatform.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion is idempotent per source: posting the same document twice updates
 * it rather than leaving two copies behind.
 *
 * <p>Against a real Postgres, because the guarantee is half schema (a unique
 * index on document.source, added in V5) and half application logic - a mock
 * of the repository would happily "prove" a constraint the database does not
 * actually enforce.
 *
 * <p>What went wrong without this: the end-to-end curl suite re-ingests two
 * runbooks on every run, so copies accumulated silently. Duplicate chunks then
 * competed for the same top-k retrieval slots and pushed other documents out,
 * which surfaced in the evaluation report as retrieval precision falling to
 * 0.33 - indistinguishable, from the report alone, from a broken retriever.
 */
@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest
@Transactional
class DocumentPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DocumentPersistence documentPersistence;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Test
    void reIngestingTheSameSourceUpdatesTheDocumentInPlace() {
        DocumentPersistence.StoredDocument first = documentPersistence.saveDocumentAndChunks(
                "Kafka Consumer Troubleshooting", "kb/kafka-consumer.md", Map.of("category", "runbook"),
                List.of("Check consumer lag first.", "Restart the consumer group."));

        DocumentPersistence.StoredDocument second = documentPersistence.saveDocumentAndChunks(
                "Kafka Consumer Troubleshooting (revised)", "kb/kafka-consumer.md", Map.of("category", "kb"),
                List.of("Raise max.poll.records."));

        assertThat(second.replaced()).isTrue();
        assertThat(second.documentId()).isEqualTo(first.documentId());

        // One row, not two - the whole point.
        assertThat(documentRepository.findAll().stream()
                .filter(d -> "kb/kafka-consumer.md".equals(d.getSource()))
                .count()).isEqualTo(1);

        Optional<Document> stored = documentRepository.findBySource("kb/kafka-consumer.md");
        assertThat(stored).isPresent();
        assertThat(stored.get().getTitle()).isEqualTo("Kafka Consumer Troubleshooting (revised)");
        assertThat(stored.get().getMetadata()).containsEntry("category", "kb");
    }

    @Test
    void theSupersededChunksAreGoneRatherThanLeftBehind() {
        documentPersistence.saveDocumentAndChunks(
                "Redis Connection Pool Exhaustion", "kb/redis.md", Map.of(),
                List.of("First stale chunk.", "Second stale chunk.", "Third stale chunk."));

        DocumentPersistence.StoredDocument replacement = documentPersistence.saveDocumentAndChunks(
                "Redis Connection Pool Exhaustion", "kb/redis.md", Map.of(),
                List.of("The only current chunk."));

        assertThat(replacement.chunkIds()).hasSize(1);
        // Stale chunks would still be retrievable, and would still hold their
        // old embeddings, so retrieval could serve content the document no
        // longer contains.
        assertThat(documentChunkRepository.findAll().stream()
                .filter(chunk -> chunk.getDocument().getId().equals(replacement.documentId()))
                .map(chunk -> chunk.getContent())
                .toList())
                .containsExactly("The only current chunk.");
    }

    @Test
    void aFirstIngestIsNotReportedAsAReplacement() {
        DocumentPersistence.StoredDocument stored = documentPersistence.saveDocumentAndChunks(
                "Shipment Tracking Guide", "kb/shipment-tracking.md", Map.of(), List.of("Select Track Package."));

        assertThat(stored.replaced()).isFalse();
    }

    /**
     * A document with no source has no natural key, so there is nothing to
     * match it against and each ingest is a genuinely new document. The unique
     * index exempts NULLs for the same reason - Postgres treats them as
     * distinct.
     */
    @Test
    void documentsWithoutASourceAreNeverTreatedAsDuplicates() {
        DocumentPersistence.StoredDocument first = documentPersistence.saveDocumentAndChunks(
                "Pasted Notes", null, Map.of(), List.of("Some pasted text."));
        DocumentPersistence.StoredDocument second = documentPersistence.saveDocumentAndChunks(
                "Pasted Notes", null, Map.of(), List.of("Different pasted text."));

        assertThat(second.replaced()).isFalse();
        assertThat(second.documentId()).isNotEqualTo(first.documentId());
    }
}
