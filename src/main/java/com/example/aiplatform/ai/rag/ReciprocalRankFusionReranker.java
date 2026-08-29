package com.example.aiplatform.ai.rag;

import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.HybridChunkMatch;
import com.example.aiplatform.model.SemanticSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion, plus a per-document cap.
 *
 * <h2>Why fuse on rank rather than on score</h2>
 *
 * Cosine distance and {@code ts_rank_cd} are not on the same scale, are not on
 * the same scale as each other <em>from query to query</em>, and neither is
 * calibrated in any absolute sense. Normalising them - min-max over the
 * candidate set, say - produces a number that looks combinable and is not: the
 * same chunk's normalised score changes depending on which other chunks
 * happened to be retrieved alongside it.
 *
 * <p>Reciprocal rank fusion sidesteps the problem by throwing the scores away
 * and keeping only the ordering each search produced:
 *
 * <pre>score(chunk) = SUM over searches of  weight / (k + rank_in_that_search)</pre>
 *
 * <p>Two properties make this work well in practice. It is scale-free, so no
 * calibration between the two searches is needed or possible to get wrong. And
 * it rewards agreement steeply: a chunk placed 2nd by both searches beats one
 * placed 1st by a single search, which is the behaviour you want, because two
 * independent methods agreeing is stronger evidence than one method being
 * confident.
 *
 * <p>{@code k} (60 by convention, and configurable) is a flattener. It bounds
 * how much better rank 1 is than rank 2 - with k=60 the difference is about
 * 1.6%, where without it rank 1 would be worth twice rank 2. That matters
 * because the top of a ranked list is where both searches are least reliable
 * and most arbitrary; a large k says "being near the top of both lists is what
 * counts", a small k says "being exactly first is what counts", and only the
 * first of those is true.
 *
 * <h2>The per-document cap</h2>
 *
 * Chunks overlap by design ({@code app.rag.chunk-overlap}), so consecutive
 * chunks from one document share text - and a document that matches the query
 * well produces several chunks that all match it well. Left alone, one document
 * can occupy every slot in the top-K with near-duplicate content: the context
 * window fills up, the token bill is paid, and the model learns nothing it did
 * not know after the first chunk. Capping per document buys back those slots
 * for a second source, which is what makes an answer able to corroborate or
 * contrast rather than only restate.
 *
 * <p>The cap is a preference, not a hard limit. If capping cannot fill the
 * requested top-K - a corpus with one relevant document - the remainder is
 * topped up in fused-score order rather than returning fewer results than
 * asked for. Fewer results would be a worse answer, and the cap exists to
 * improve answers.
 */
@Component
public class ReciprocalRankFusionReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ReciprocalRankFusionReranker.class);

    private final RagProperties ragProperties;

    public ReciprocalRankFusionReranker(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @Override
    public List<SemanticSearchResult> rerank(List<HybridChunkMatch> candidates, int limit) {
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (HybridChunkMatch candidate : candidates) {
            scored.add(new Scored(candidate, fusedScore(candidate)));
        }
        // Ties broken by cosine distance so the ordering is total and stable -
        // two chunks found only by the lexical search at adjacent ranks would
        // otherwise come back in whatever order the join produced them, making
        // the same query return differently-ordered results run to run.
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingDouble(entry -> entry.match().distance()));

        List<Scored> selected = applyPerDocumentCap(scored, limit);

        log.debug("Fused {} candidate(s) into {} result(s): {}", candidates.size(), selected.size(),
                selected.stream()
                        .map(entry -> entry.match().documentTitle() + "(dense=" + entry.match().denseRank()
                                + ",lex=" + entry.match().lexicalRank() + ",rrf=" + entry.score() + ")")
                        .toList());

        return selected.stream().map(Scored::toResult).toList();
    }

    private double fusedScore(HybridChunkMatch candidate) {
        int k = ragProperties.fusionK();
        double score = 0.0;
        if (candidate.matchedDensely()) {
            score += ragProperties.denseWeight() / (k + candidate.denseRank());
        }
        if (candidate.matchedLexically()) {
            score += ragProperties.lexicalWeight() / (k + candidate.lexicalRank());
        }
        return score;
    }

    /**
     * One pass honouring the cap, then a second pass topping up from what the
     * cap rejected. Two passes rather than one so the cap never costs the
     * caller results - the second pass only runs when the first could not fill
     * the request.
     */
    private List<Scored> applyPerDocumentCap(List<Scored> scored, int limit) {
        int maxPerDocument = Math.max(1, ragProperties.maxChunksPerDocument());
        Map<Long, Integer> perDocument = new HashMap<>();
        List<Scored> selected = new ArrayList<>(limit);
        List<Scored> deferred = new ArrayList<>();

        for (Scored entry : scored) {
            if (selected.size() == limit) {
                break;
            }
            long documentId = entry.match().documentId();
            int taken = perDocument.getOrDefault(documentId, 0);
            if (taken >= maxPerDocument) {
                deferred.add(entry);
                continue;
            }
            perDocument.put(documentId, taken + 1);
            selected.add(entry);
        }

        for (Scored entry : deferred) {
            if (selected.size() == limit) {
                break;
            }
            selected.add(entry);
        }
        // The top-up can append an entry that outscores something the cap let
        // through earlier, so the final order is restored rather than assumed.
        selected.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparingDouble(entry -> entry.match().distance()));
        return selected;
    }

    private record Scored(HybridChunkMatch match, double score) {

        SemanticSearchResult toResult() {
            return new SemanticSearchResult(match.documentTitle(), match.content(),
                    1 - match.distance(), match.matchedLexically(), score);
        }
    }
}
