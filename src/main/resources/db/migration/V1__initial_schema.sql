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

-- customer_id is only meaningful for USER-role accounts (a customer looking
-- up their own data); staff accounts (SUPPORT_AGENT, ADMIN) leave it NULL,
-- since tool-level authorization never checks ownership for staff roles.
CREATE TABLE IF NOT EXISTS app_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    customer_id VARCHAR(50),
    role VARCHAR(30) NOT NULL
);
