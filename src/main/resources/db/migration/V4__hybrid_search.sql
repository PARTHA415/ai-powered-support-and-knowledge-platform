-- Lexical half of hybrid retrieval.
--
-- Dense (embedding) retrieval and lexical (full-text) retrieval fail in
-- opposite directions, which is exactly why running both beats running either.
-- An embedding is a summary of meaning, and a summary is where exact tokens go
-- to die: an error code, a config key, a class name, a version string - the
-- terms an operator actually types when something is broken - are precisely
-- what a 1536-dimension average smooths away. Full-text search finds them
-- trivially and, in return, cannot tell "consumer group rebalancing" from
-- "partition reassignment" at all.
--
-- A GENERATED column rather than a trigger-maintained one: Postgres recomputes
-- it on write, so the tsvector cannot drift out of sync with the content it
-- indexes. There is no code path anywhere in the application that can update
-- one without the other, which is a stronger guarantee than remembering to.
--
-- 'english' is hard-coded because a generated column's expression must be
-- IMMUTABLE, and to_tsvector's one-argument form depends on a session setting
-- (default_text_search_config), which is not. Supporting a second language
-- therefore means a second column and a second index, not a configuration
-- change - a real limitation, and worth knowing before a non-English corpus
-- arrives rather than after.
ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

-- GIN, not GiST. GIN is slower to build and larger on disk; it is also
-- substantially faster to search, and this column is written once per chunk at
-- ingestion and read on every question.
CREATE INDEX IF NOT EXISTS document_chunk_content_tsv_idx
    ON document_chunk
    USING gin (content_tsv);
