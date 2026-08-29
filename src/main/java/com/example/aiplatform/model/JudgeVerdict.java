package com.example.aiplatform.model;

/**
 * What an LLM judge concluded about one answer.
 *
 * <p>Two independent scores rather than one overall verdict, because they fail
 * independently and the fix differs: a low groundedness score is a generation
 * or prompt problem, a low relevance score with high groundedness usually means
 * retrieval returned the wrong documents and the model faithfully answered from
 * them. Collapsing them into a single number would hide exactly the distinction
 * that tells you where to look.
 *
 * <p>Each score carries its own reason. A judge score with no reason is not
 * actionable - nobody can act on "0.4" - and requiring the reason also improves
 * the score, because a model that must justify a number commits to a specific
 * claim rather than to a vague impression.
 */
public record JudgeVerdict(
        double groundedness,
        String groundednessReason,
        double relevance,
        String relevanceReason
) {

    /** The verdict used when the judge could not be reached or could not be parsed. */
    public static JudgeVerdict unavailable(String reason) {
        return new JudgeVerdict(-1, reason, -1, reason);
    }

    /**
     * Whether this verdict carries real scores.
     *
     * <p>A failed judge call reports as unavailable rather than as zero. Scoring
     * a provider outage as "the answer was completely ungrounded" would corrupt
     * every aggregate it entered and would look, in a report, exactly like a
     * catastrophic quality regression.
     */
    public boolean isAvailable() {
        return groundedness >= 0 && relevance >= 0;
    }
}
