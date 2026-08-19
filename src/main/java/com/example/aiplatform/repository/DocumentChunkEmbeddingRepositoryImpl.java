package com.example.aiplatform.repository;

import com.example.aiplatform.model.SimilarChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Repository
public class DocumentChunkEmbeddingRepositoryImpl implements DocumentChunkEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkEmbeddingRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveEmbedding(Long chunkId, float[] embedding) {
        jdbcTemplate.update(
                "UPDATE document_chunk SET embedding = CAST(? AS vector) WHERE id = ?",
                toPgVectorLiteral(embedding), chunkId);
    }

    @Override
    public List<SimilarChunk> findNearest(float[] queryEmbedding, int limit) {
        String literal = toPgVectorLiteral(queryEmbedding);
        return jdbcTemplate.query(
                """
                SELECT c.id AS chunk_id, c.document_id AS document_id, d.title AS document_title,
                       c.content AS content, c.embedding <=> CAST(? AS vector) AS distance
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE c.embedding IS NOT NULL
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT ?
                """,
                (rs, rowNum) -> new SimilarChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_title"),
                        rs.getString("content"),
                        rs.getDouble("distance")),
                literal, literal, limit);
    }

    private static String toPgVectorLiteral(float[] embedding) {
        return IntStream.range(0, embedding.length)
                .mapToObj(i -> Float.toString(embedding[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
