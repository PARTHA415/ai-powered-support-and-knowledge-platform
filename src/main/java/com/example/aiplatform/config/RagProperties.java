package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable knobs for the RAG pipeline: how documents get split into chunks at
 * ingestion time, how candidates are retrieved and fused at question-answering
 * time, and where the relevance bar sits. Deliberately global config rather
 * than per-request parameters - these are pipeline-tuning concerns, not
 * something a caller of /api/qa should be adjusting per call.
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(

        /**
         * Target chunk size in TOKENS, not characters.
         *
         * <p>Characters were the wrong unit and produced two failures at once.
         * Every downstream limit that matters - the model's context window, the
         * prompt-size guardrail, the price of a call - is denominated in tokens,
         * and the characters-per-token ratio is not a constant: prose runs about
         * 4:1, but a chunk of YAML, a stack trace, or a table of error codes can
         * run closer to 2:1. So a fixed 800-character chunk was somewhere
         * between 200 and 400 tokens depending on content, meaning the densest,
         * most information-rich chunks in a technical corpus - exactly the ones
         * worth retrieving - were systematically the largest and most likely to
         * blow a context budget nobody had measured.
         *
         * <p>Counting the real thing removes the guesswork. See
         * {@link com.example.aiplatform.ai.rag.TokenAwareChunker}.
         */
        @DefaultValue("220") int chunkTokens,

        /**
         * Overlap between consecutive chunks, in tokens. Guards against a fact
         * landing exactly on a chunk boundary and becoming unretrievable from
         * either side of the cut.
         */
        @DefaultValue("40") int chunkOverlapTokens,

        /**
         * How many chunks are embedded per provider call during ingestion.
         * Batched rather than one call for a whole document because providers
         * cap request size and tokens per request; a fixed batch keeps one
         * oversized document from producing one oversized request.
         */
        @DefaultValue("32") int embeddingBatchSize,

        /** How many chunks a retrieval call returns to the prompt builder. */
        @DefaultValue("5") int topK,

        /**
         * Minimum cosine similarity for a DENSE match to count as relevant.
         *
         * <p>Still the least principled number in this file, and now at least
         * measurable: {@code POST /api/eval/calibrate-threshold} sweeps it
         * against the labelled RAG dataset and reports the F1 at each value, so
         * the setting can be chosen from the corpus rather than guessed. It
         * does not apply to lexical matches - see
         * {@link com.example.aiplatform.model.SemanticSearchResult#isRelevantAt(double)}.
         */
        @DefaultValue("0.5") double similarityThreshold,

        /**
         * Whether to run the full-text search alongside the vector search.
         *
         * <p>On by default, with a switch, because hybrid retrieval depends on
         * the V4 migration's generated column. An operator who needs to fall
         * back to dense-only - to isolate a regression, or on a corpus where the
         * English text-search configuration is wrong - can do it without a
         * deploy.
         */
        @DefaultValue("true") boolean hybridEnabled,

        /**
         * How many candidates EACH search contributes, as a multiple of topK.
         *
         * <p>Re-ranking can only reorder what it was given, so retrieving
         * exactly topK from each search and then fusing achieves almost
         * nothing - the interesting result is usually the one that placed 8th
         * in one search and 2nd in the other, and at a multiplier of 1 it was
         * never retrieved. Retrieve wide, rank narrow. The cost is a larger
         * intermediate result set, not a larger prompt.
         */
        @DefaultValue("4") int candidateMultiplier,

        /**
         * The {@code k} constant in reciprocal rank fusion. 60 is the value from
         * the original RRF paper and a sane default: large enough that "near the
         * top of both lists" beats "first in one list", which is the behaviour
         * fusion exists to produce.
         */
        @DefaultValue("60") int fusionK,

        /** Relative weight of the vector search in the fusion. */
        @DefaultValue("1.0") double denseWeight,

        /**
         * Relative weight of the full-text search in the fusion. Equal to the
         * dense weight by default: neither search is generally better, they are
         * better at different queries, and picking a winner up front would
         * defeat running both.
         */
        @DefaultValue("1.0") double lexicalWeight,

        /**
         * How many chunks one document may contribute to a single result set,
         * before the top-up pass. Two, because overlapping chunks from one
         * document are near-duplicates and a fifth slot spent on a third copy of
         * the same paragraph is a slot not spent on a second source.
         */
        @DefaultValue("2") int maxChunksPerDocument
) {

    /** How many candidates each half of hybrid retrieval should return. */
    public int candidateLimit() {
        return Math.max(topK, topK * Math.max(1, candidateMultiplier));
    }
}
