package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The semantic answer cache: whether it runs, how similar two questions must be
 * to count as the same question, and how much is kept.
 *
 * @param enabled           on by default. Support traffic is unusually
 *                          repetitive - the same handful of questions arrive
 *                          all day in slightly different words - which is the
 *                          workload semantic caching is for.
 * @param similarityThreshold how close a cached question's embedding must be to
 *                          the incoming one. Deliberately high (0.95), and the
 *                          single most consequential number here. A semantic
 *                          cache does not return a slightly stale answer when it
 *                          is wrong - it returns the answer to a DIFFERENT
 *                          question, confidently, and the user has no way to
 *                          tell. "How do I rotate the Kafka consumer's
 *                          credentials" and "how do I rotate the Kafka broker's
 *                          certificates" are close in embedding space and are
 *                          not the same question. Tune this down only with
 *                          evaluation evidence, never to raise the hit rate.
 * @param maxEntriesPerScope how many recent question/answer pairs are retained
 *                          per authorization scope. Bounded because lookup
 *                          compares against every entry (see the implementation
 *                          for why that is acceptable here and what a larger
 *                          deployment would do instead).
 * @param ttl               how long an entry survives. A knowledge base changes;
 *                          an answer cached from a document that has since been
 *                          re-ingested is stale in a way nothing detects, so
 *                          entries expire on time rather than on invalidation.
 */
@ConfigurationProperties(prefix = "app.ai.cache.semantic")
public record SemanticCacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0.95") double similarityThreshold,
        @DefaultValue("200") int maxEntriesPerScope,
        @DefaultValue("2h") Duration ttl
) {
}
