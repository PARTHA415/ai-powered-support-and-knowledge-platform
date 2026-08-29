package com.example.aiplatform.model;

/**
 * One candidate from hybrid retrieval, carrying where it placed in each of the
 * two searches that ran.
 *
 * <p>Both ranks are nullable, and that is the whole point of the type: a chunk
 * can be found by the vector search only, by the full-text search only, or by
 * both, and which of those happened is the signal the fusion step ranks on. A
 * chunk that both searches surfaced independently is far stronger evidence than
 * one either found alone - see
 * {@link com.example.aiplatform.ai.rag.ReciprocalRankFusionReranker}.
 *
 * <p>{@code distance} is always populated - a real cosine distance computed for
 * every candidate including the lexical-only ones - so the similarity a caller
 * sees means the same thing regardless of which search found the chunk. That is
 * worth the small extra computation: a "similarity" that silently meant
 * something different for some rows would be worse than no number at all.
 *
 * @param denseRank    1-based position in the vector search, or null if the
 *                     vector search did not return it
 * @param lexicalRank  1-based position in the full-text search, or null
 * @param lexicalScore the raw {@code ts_rank_cd} score, 0 when there was no
 *                     lexical match. Kept for diagnostics and for the eval
 *                     harness; the fusion deliberately ranks on position rather
 *                     than on this, because the two searches' scores are not on
 *                     a comparable scale
 */
public record HybridChunkMatch(
        Long chunkId,
        Long documentId,
        String documentTitle,
        String content,
        double distance,
        Integer denseRank,
        Integer lexicalRank,
        double lexicalScore
) {

    public boolean matchedLexically() {
        return lexicalRank != null;
    }

    public boolean matchedDensely() {
        return denseRank != null;
    }
}
