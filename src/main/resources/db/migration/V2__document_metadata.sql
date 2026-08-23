-- Metadata filtering: the capability the original brief asked for, which the
-- schema had no columns to support.
--
-- jsonb rather than a fixed set of columns because the useful facets are not
-- known up front and differ per corpus - product, component, version, audience,
-- language. A jsonb column absorbs new facets without a migration each time;
-- the cost is that nothing constrains the keys, which is the right trade for a
-- knowledge base whose shape is still moving.
ALTER TABLE document ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

-- GIN with jsonb_path_ops, not the default jsonb_ops. Retrieval only ever asks
-- containment questions ("does this document's metadata contain these
-- key/value pairs", the @> operator), and jsonb_path_ops indexes exactly that
-- at roughly half the size and with faster lookups. It cannot answer key-
-- existence questions (?), which this application does not ask.
CREATE INDEX IF NOT EXISTS document_metadata_idx
    ON document
    USING gin (metadata jsonb_path_ops);
