package com.example.aiplatform.repository;

import com.example.aiplatform.model.HybridChunkMatch;
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

    /**
     * The eligibility predicate shared by both halves of hybrid retrieval, as
     * one string used in two places rather than two strings that must be kept
     * identical. Divergence here would be a security bug, not a relevance one:
     * the metadata exclusion carrying the audience policy is in this clause,
     * and a lexical branch that forgot it would let a customer retrieve an
     * internal runbook by typing a term from it.
     */
    private static final String ELIGIBILITY_CLAUSE = """
            c.embedding IS NOT NULL
              AND c.embedding_model = ?
              AND (CAST(? AS boolean) = false OR d.metadata @> CAST(? AS jsonb))
              AND (CAST(? AS boolean) = false OR NOT (d.metadata @> CAST(? AS jsonb)))
            """;

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
        Object[] eligibility = eligibilityParameters(embeddingModel, filter);

        String sql = """
                SELECT c.id AS chunk_id, c.document_id AS document_id, d.title AS document_title,
                       c.content AS content, c.embedding <=> CAST(? AS vector) AS distance
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE %s
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT ?
                """.formatted(ELIGIBILITY_CLAUSE);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new SimilarChunk(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_title"),
                        rs.getString("content"),
                        rs.getDouble("distance")),
                concat(new Object[] {literal}, eligibility, new Object[] {literal, limit}));
    }

    /**
     * The two searches, run over the same eligible corpus, unioned by chunk id.
     *
     * <h2>Why the shape is what it is</h2>
     *
     * <p><b>Each search LIMITs before its rank is computed.</b> The rank is
     * assigned by a window function in an outer query over an already-limited
     * inner one. Putting {@code ROW_NUMBER() OVER (ORDER BY distance)} directly
     * beside the {@code LIMIT} would force Postgres to order every eligible row
     * before discarding all but a handful - which is exactly the full sort the
     * HNSW index exists to avoid, and would quietly turn an indexed
     * nearest-neighbour lookup into a sequential scan of the corpus.
     *
     * <p><b>The filter predicate is repeated rather than shared through a
     * CTE.</b> A CTE referenced twice is materialised by Postgres, and a
     * materialised intermediate result cannot be served by the HNSW or GIN
     * indexes - the planner has already lost the ability to push the ordering
     * into them. Repeating the predicate is more SQL and a faster query; the
     * risk of the two copies drifting is handled by there being only one copy
     * in the source ({@link #ELIGIBILITY_CLAUSE}).
     *
     * <p><b>The lexical branch computes a cosine distance too.</b> Slightly
     * redundant work on at most {@code candidateLimit} rows, and it is what lets
     * every candidate carry a comparable similarity regardless of which search
     * found it. Without it, a lexical-only hit would have to report a null or
     * an invented similarity, and every consumer downstream would need to know
     * which.
     *
     * <p><b>{@code plainto_tsquery}, not {@code to_tsquery}.</b> The query text
     * is whatever a user typed. {@code to_tsquery} parses operators out of it
     * and raises a syntax error on input it does not like, which would turn a
     * question containing an ampersand into a 500;
     * {@code plainto_tsquery} treats the whole string as words to AND together
     * and cannot be given a malformed query. It is bound as a parameter either
     * way, so this is about robustness rather than injection - the injection
     * question was already settled by not concatenating it.
     */
    @Override
    public List<HybridChunkMatch> findHybridCandidates(float[] queryEmbedding, String queryText, int candidateLimit,
                                                         String embeddingModel, RetrievalFilter filter) {
        String literal = toPgVectorLiteral(queryEmbedding);
        Object[] eligibility = eligibilityParameters(embeddingModel, filter);

        String sql = """
                WITH dense AS (
                    SELECT chunk_id, distance,
                           ROW_NUMBER() OVER (ORDER BY distance) AS dense_rank
                    FROM (
                        SELECT c.id AS chunk_id, c.embedding <=> CAST(? AS vector) AS distance
                        FROM document_chunk c
                        JOIN document d ON d.id = c.document_id
                        WHERE %1$s
                        ORDER BY c.embedding <=> CAST(? AS vector)
                        LIMIT ?
                    ) top_dense
                ),
                lexical AS (
                    SELECT chunk_id, distance, lexical_score,
                           ROW_NUMBER() OVER (ORDER BY lexical_score DESC) AS lexical_rank
                    FROM (
                        SELECT c.id AS chunk_id,
                               c.embedding <=> CAST(? AS vector) AS distance,
                               ts_rank_cd(c.content_tsv, plainto_tsquery('english', ?)) AS lexical_score
                        FROM document_chunk c
                        JOIN document d ON d.id = c.document_id
                        WHERE %1$s
                          AND c.content_tsv @@ plainto_tsquery('english', ?)
                        ORDER BY lexical_score DESC
                        LIMIT ?
                    ) top_lexical
                ),
                candidates AS (
                    SELECT chunk_id FROM dense
                    UNION
                    SELECT chunk_id FROM lexical
                )
                SELECT c.id AS chunk_id, c.document_id AS document_id, d.title AS document_title,
                       c.content AS content,
                       COALESCE(dense.distance, lexical.distance) AS distance,
                       dense.dense_rank AS dense_rank,
                       lexical.lexical_rank AS lexical_rank,
                       COALESCE(lexical.lexical_score, 0) AS lexical_score
                FROM candidates
                JOIN document_chunk c ON c.id = candidates.chunk_id
                JOIN document d ON d.id = c.document_id
                LEFT JOIN dense ON dense.chunk_id = candidates.chunk_id
                LEFT JOIN lexical ON lexical.chunk_id = candidates.chunk_id
                """.formatted(ELIGIBILITY_CLAUSE);

        Object[] parameters = concat(
                new Object[] {literal}, eligibility, new Object[] {literal, candidateLimit},
                new Object[] {literal, queryText}, eligibility, new Object[] {queryText, candidateLimit});

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new HybridChunkMatch(
                        rs.getLong("chunk_id"),
                        rs.getLong("document_id"),
                        rs.getString("document_title"),
                        rs.getString("content"),
                        rs.getDouble("distance"),
                        nullableInt(rs.getObject("dense_rank")),
                        nullableInt(rs.getObject("lexical_rank")),
                        rs.getDouble("lexical_score")),
                parameters);
    }

    private Object[] eligibilityParameters(String embeddingModel, RetrievalFilter filter) {
        RetrievalFilter effective = filter == null ? RetrievalFilter.none() : filter;
        return new Object[] {
                embeddingModel,
                effective.hasInclusions(), effective.hasInclusions() ? toJson(effective.mustContain()) : "{}",
                effective.hasExclusions(), effective.hasExclusions() ? toJson(effective.mustNotContain()) : "{}"
        };
    }

    private static Integer nullableInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Object[] concat(Object[]... groups) {
        return java.util.Arrays.stream(groups).flatMap(java.util.Arrays::stream).toArray();
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
