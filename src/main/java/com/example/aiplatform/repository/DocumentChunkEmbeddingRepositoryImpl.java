package com.example.aiplatform.repository;

import com.example.aiplatform.model.RetrievalFilter;
import com.example.aiplatform.model.SimilarChunk;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Repository
public class DocumentChunkEmbeddingRepositoryImpl implements DocumentChunkEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocumentChunkEmbeddingRepositoryImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveEmbedding(Long chunkId, float[] embedding, String embeddingModel) {
        jdbcTemplate.update(
                "UPDATE document_chunk SET embedding = CAST(? AS vector), embedding_model = ? WHERE id = ?",
                toPgVectorLiteral(embedding), embeddingModel, chunkId);
    }

    /**
     * Nearest-neighbour search, narrowed two ways before distance is ever
     * considered.
     *
     * <p><b>Embedding model.</b> Only chunks embedded by the model currently
     * in use are eligible. Cosine distance between vectors from two different
     * embedding models is meaningless, so a mixed index does not return worse
     * results - it returns arbitrary ones, silently. Filtering here means
     * switching models degrades to "no relevant documentation found", which
     * the RAG prompt already renders honestly, rather than to confident
     * nonsense. The remedy for that state is a re-embed backfill.
     *
     * <p><b>Metadata.</b> An optional containment filter on the parent
     * document's jsonb metadata, applied as a WHERE clause rather than by
     * post-filtering the results. That distinction matters: post-filtering
     * top-K would return fewer than K rows, sometimes zero, because the filter
     * throws away results the index already committed to. Pre-filtering asks
     * for the nearest K rows <em>that match</em>, which is what a caller
     * asking for five results expects.
     *
     * <p>The filter is passed as a single jsonb parameter and matched with
     * {@code @>} (containment), so caller-supplied keys and values are bound
     * data, never concatenated SQL, and the query shape stays constant no
     * matter what the filter contains - which is also what lets the
     * jsonb_path_ops GIN index serve it. The exclusion half
     * ({@code mustNotContain}) is a policy decision about the caller, never
     * caller input, and is combined with AND NOT so no request can widen what
     * it may see.
     */
    @Override
    public List<SimilarChunk> findNearest(float[] queryEmbedding, int limit, String embeddingModel,
                                           RetrievalFilter filter) {
        String literal = toPgVectorLiteral(queryEmbedding);
        RetrievalFilter effective = filter == null ? RetrievalFilter.none() : filter;
        String includeJson = effective.hasInclusions() ? toJson(effective.mustContain()) : "{}";
        String excludeJson = effective.hasExclusions() ? toJson(effective.mustNotContain()) : "{}";

        String sql = """
                SELECT c.id AS chunk_id, c.document_id AS document_id, d.title AS document_title,
                       c.content AS content, c.embedding <=> CAST(? AS vector) AS distance
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE c.embedding IS NOT NULL
                  AND c.embedding_model = ?
                  AND (CAST(? AS boolean) = false OR d.metadata @> CAST(? AS jsonb))
                  AND (CAST(? AS boolean) = false OR NOT (d.metadata @> CAST(? AS jsonb)))
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT ?
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SimilarChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_title"),
                        rs.getString("content"),
                        rs.getDouble("distance")),
                literal, embeddingModel,
                effective.hasInclusions(), includeJson,
                effective.hasExclusions(), excludeJson,
                literal, limit);
    }

    private String toJson(Map<String, String> metadataFilter) {
        try {
            return objectMapper.writeValueAsString(metadataFilter);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Metadata filter could not be serialized to JSON", e);
        }
    }

    private static String toPgVectorLiteral(float[] embedding) {
        return IntStream.range(0, embedding.length)
                .mapToObj(i -> Float.toString(embedding[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
