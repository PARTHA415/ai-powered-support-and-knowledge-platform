package com.example.aiplatform.repository;

import com.example.aiplatform.model.Document;
import com.example.aiplatform.model.DocumentChunk;
import com.example.aiplatform.model.RetrievalFilter;
import com.example.aiplatform.model.SimilarChunk;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres + pgvector via Testcontainers - no mocking of the database.
 * schema.sql (CREATE EXTENSION vector, tables, the HNSW index) runs
 * automatically against this container because spring.sql.init.mode=always
 * is unconditional, not just for embedded databases.
 *
 * @Transactional rolls each test method's changes back afterward, so the
 * three tests below (which all write to the same shared container) can't
 * see each other's rows regardless of execution order.
 */
@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest
@Transactional
class DocumentChunkEmbeddingRepositoryImplIT {

    // Must match document_chunk.embedding's fixed dimension in schema.sql (1536,
    // matching Phase 4's text-embedding-3-small) - pgvector rejects mismatched sizes.
    private static final int EMBEDDING_DIMENSIONS = 1536;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    private static final String MODEL = "text-embedding-3-small";

    @Test
    void findNearestOrdersByCosineDistanceClosestFirst() {
        Document document = documentRepository.save(new Document("Password Reset Guide", "kb/password-reset.md"));
        DocumentChunk closeChunk = documentChunkRepository.save(
                new DocumentChunk(document, 0, "Go to Settings > Security > Reset Password."));
        DocumentChunk farChunk = documentChunkRepository.save(
                new DocumentChunk(document, 1, "Our office is located at 123 Main Street."));

        documentChunkRepository.saveEmbedding(closeChunk.getId(), unitVector(0), MODEL);
        documentChunkRepository.saveEmbedding(farChunk.getId(), unitVector(2), MODEL);

        List<SimilarChunk> results = documentChunkRepository.findNearest(unitVector(0), 5, MODEL, RetrievalFilter.none());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).chunkId()).isEqualTo(closeChunk.getId());
        assertThat(results.get(0).distance()).isLessThan(results.get(1).distance());
        assertThat(results.get(0).documentTitle()).isEqualTo("Password Reset Guide");
    }

    @Test
    void findNearestRespectsLimit() {
        Document document = documentRepository.save(new Document("Limit Test Doc", null));
        for (int i = 0; i < 5; i++) {
            DocumentChunk chunk = documentChunkRepository.save(new DocumentChunk(document, i, "chunk " + i));
            documentChunkRepository.saveEmbedding(chunk.getId(), unitVector(0), MODEL);
        }

        List<SimilarChunk> results = documentChunkRepository.findNearest(unitVector(0), 3, MODEL, RetrievalFilter.none());

        assertThat(results).hasSize(3);
    }

    @Test
    void findNearestSkipsChunksWithoutAnEmbedding() {
        Document document = documentRepository.save(new Document("Partial Doc", null));
        documentChunkRepository.save(new DocumentChunk(document, 0, "never embedded"));

        List<SimilarChunk> results = documentChunkRepository.findNearest(unitVector(0), 5, MODEL, RetrievalFilter.none());

        assertThat(results).isEmpty();
    }

    private static float[] unitVector(int dimension) {
        float[] vector = new float[EMBEDDING_DIMENSIONS];
        vector[dimension] = 1.0f;
        return vector;
    }
}
