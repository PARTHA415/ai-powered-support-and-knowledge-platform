-- Records which embedding model produced each stored vector.
--
-- Without this there was no way to detect a mixed index. Changing
-- OPENAI_EMBEDDING_MODEL silently starts writing vectors from a different
-- embedding space into the same column, and cosine distance between vectors
-- from two different models is meaningless - retrieval quality collapses with
-- no error, no log line, and nothing to query to find out it happened.
--
-- Retrieval now filters on this column (see DocumentChunkEmbeddingRepositoryImpl),
-- so a model change degrades to "no results found" - which the RAG prompt
-- already renders honestly - instead of returning confident nonsense. The fix
-- for that state is a re-embed backfill, and this column is what makes such a
-- backfill possible to write.
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);

-- Backfill for rows written before this column existed. text-embedding-3-small
-- was the only configured default up to this point (see application.yml), and
-- vector(1536) matches its output dimension, so any existing vector came from
-- it. Rows with no embedding are left NULL - there is nothing to attribute.
UPDATE document_chunk
   SET embedding_model = 'text-embedding-3-small'
 WHERE embedding IS NOT NULL
   AND embedding_model IS NULL;

CREATE INDEX IF NOT EXISTS document_chunk_embedding_model_idx
    ON document_chunk (embedding_model);
