CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    source VARCHAR(1000),
    created_at TIMESTAMP NOT NULL
);

-- 1536 = the output dimension of text-embedding-3-small (see Phase 4). Fixed at
-- table-creation time by pgvector; changing embedding models later means a new
-- column (and a full re-index), not a config change.
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS document_chunk_embedding_idx
    ON document_chunk
    USING hnsw (embedding vector_cosine_ops);
