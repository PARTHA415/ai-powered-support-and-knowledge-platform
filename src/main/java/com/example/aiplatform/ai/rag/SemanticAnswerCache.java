package com.example.aiplatform.ai.rag;

import com.example.aiplatform.model.AskResponse;

import java.util.Optional;

/**
 * Answers a question that has effectively been asked before, without asking the
 * model again.
 *
 * <h2>Semantic, not exact</h2>
 *
 * An exact-match answer cache is close to useless on natural-language input:
 * "how do I fix Kafka consumer lag" and "kafka consumer lag - how to fix?" are
 * the same question and share no cache key. Keying on the question's EMBEDDING
 * instead means paraphrases hit, which is where nearly all the repetition in
 * support traffic lives.
 *
 * <h2>What it costs when it is wrong</h2>
 *
 * This is the part worth being careful about. An ordinary cache that is wrong
 * returns a stale value - the right answer, out of date. A semantic cache that
 * is wrong returns the answer to a <em>different question</em>, in fluent prose,
 * with sources attached, and neither the user nor any downstream check can tell.
 * The threshold is the only thing standing between those two outcomes, which is
 * why it defaults high and why raising the hit rate is not on its own a reason
 * to lower it.
 *
 * <h2>Scope, and why it is an authorization boundary</h2>
 *
 * Cached entries are partitioned by the caller's retrieval scope - the audience
 * exclusions {@link KnowledgeBaseAccessPolicy} computes for them. Without that
 * partition the cache would be a hole straight through the per-document access
 * control: a support agent asks a question, the answer is synthesised from an
 * internal runbook, and the next customer to ask something similar is served
 * that answer from cache without a single document ever being retrieved on their
 * behalf. Retrieval-time filtering cannot help, because with a cache hit there
 * is no retrieval. The partition is what keeps the cache from silently becoming
 * the most permissive path in the application.
 *
 * <p>For the same reason only the knowledge-base question-answering path is
 * cached. Tool-calling and agent answers contain per-customer data - an order
 * status, a payment amount - and no scope key short of the customer id would
 * make them safe to share. The value of caching them is also close to zero:
 * "where is my order" has a different answer for every caller and a different
 * answer tomorrow.
 */
public interface SemanticAnswerCache {

    /** A previously-computed answer to a question close enough to be the same one. */
    Optional<AskResponse> lookup(String question, float[] questionEmbedding);

    /** Remembers an answer against the question that produced it. */
    void store(String question, float[] questionEmbedding, AskResponse response);
}
