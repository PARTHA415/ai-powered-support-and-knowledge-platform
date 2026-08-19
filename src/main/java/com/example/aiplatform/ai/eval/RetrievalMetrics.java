package com.example.aiplatform.ai.eval;

/**
 * precision = of what we retrieved, how much was actually relevant.
 * recall = of what was actually relevant, how much did we retrieve.
 * A retriever can be precise but low-recall (returns 1 chunk, it's correct,
 * but misses 2 other relevant ones), or high-recall but imprecise (returns
 * everything, so nothing relevant is missed, but most of it is noise) -
 * neither number alone tells the full story, which is why both are tracked.
 */
public record RetrievalMetrics(double precision, double recall) {
}
