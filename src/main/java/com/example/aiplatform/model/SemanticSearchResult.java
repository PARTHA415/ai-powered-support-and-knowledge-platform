package com.example.aiplatform.model;

/**
 * A retrieved chunk as the rest of the application sees it.
 *
 * <p>{@code similarity} is 1 - cosine distance, so higher is better (1.0 =
 * identical) - more intuitive for API consumers than exposing raw distance
 * directly. It means the same thing for every result regardless of which
 * half of hybrid retrieval found it.
 *
 * @param lexicalMatch whether the full-text search independently surfaced this
 *                     chunk. Load-bearing, not informational - see
 *                     {@link #isRelevantAt(double)}.
 * @param fusedScore   the reciprocal-rank-fusion score the results were ordered
 *                     by. Exposed so the evaluation harness can see WHY an
 *                     ordering came out the way it did rather than only that it
 *                     did; 0 when hybrid retrieval is disabled and ordering is
 *                     pure cosine.
 */
public record SemanticSearchResult(
        String documentTitle,
        String content,
        double similarity,
        boolean lexicalMatch,
        double fusedScore
) {

    /** Dense-only result, for callers and tests that have no fusion information. */
    public SemanticSearchResult(String documentTitle, String content, double similarity) {
        this(documentTitle, content, similarity, false, 0.0);
    }

    /**
     * Whether this chunk clears the relevance bar.
     *
     * <p>The cosine threshold applies to <em>dense</em> matches only. A chunk
     * the full-text search returned is admitted regardless of its cosine
     * similarity, and that is deliberate rather than a loophole: a lexical hit
     * means the query's actual terms are present in the chunk, which is direct
     * evidence of relevance and does not need a semantic score to corroborate
     * it. Requiring one would throw away precisely the results hybrid retrieval
     * was added to find - an exact error code or config key sitting in a chunk
     * whose overall topic embeds nowhere near the question.
     *
     * <p>The rule lives here rather than in each caller because three of them
     * apply it ({@code /api/qa}, the agent's knowledge-base step, and the
     * evaluation harness), and a relevance rule that three places implement
     * separately is a relevance rule that will diverge.
     */
    public boolean isRelevantAt(double similarityThreshold) {
        return lexicalMatch || similarity >= similarityThreshold;
    }
}
