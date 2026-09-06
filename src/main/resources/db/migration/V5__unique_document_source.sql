-- Makes re-ingesting the same document an UPDATE rather than a second copy.
--
-- Why this was needed: the end-to-end curl suite ingests two runbooks on every
-- run, and nothing stopped them accumulating. Three runs left three copies of
-- each. Duplicates are not merely untidy - identical chunks compete for the
-- same top-k retrieval slots, so a query that should return five distinct
-- documents returns the same one three times and crowds the others out. It
-- showed up as retrieval precision falling to 0.33 in the evaluation report,
-- which reads exactly like a retrieval regression and is not one.
--
-- "source" is the natural key: a path or URI identifying where the document
-- came from (kb/kafka-consumer.md). Title is not - two different runbooks may
-- legitimately share a title, and a document may be retitled without becoming
-- a different document.

-- Existing duplicates must go before the index can be built. Keep the oldest
-- row per source: its id is the one anything else may already refer to.
-- Chunks (and their embeddings) cascade from document.
DELETE FROM document d
      WHERE d.source IS NOT NULL
        AND d.id > (SELECT MIN(keep.id) FROM document keep WHERE keep.source = d.source);

-- NULL sources are intentionally exempt. Postgres treats NULLs as distinct in
-- a unique index, so documents ingested without a source are never deduplicated
-- against each other - correct, because there is no key to deduplicate them by.
CREATE UNIQUE INDEX document_source_key ON document (source);
