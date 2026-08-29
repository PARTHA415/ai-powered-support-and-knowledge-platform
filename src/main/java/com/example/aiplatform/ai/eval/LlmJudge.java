package com.example.aiplatform.ai.eval;

import com.example.aiplatform.model.JudgeVerdict;
import com.example.aiplatform.model.SemanticSearchResult;

import java.util.List;

/**
 * Scores an answer for groundedness and relevance by asking a model, rather
 * than by counting word overlap.
 *
 * <h2>What the lexical scorers cannot do</h2>
 *
 * {@link AnswerQualityScorer#groundednessScore} measures the fraction of an
 * answer's content words that also appear in the retrieved context. That
 * catches vocabulary invention - an answer naming a config key nothing
 * retrieved mentions - and it is fast, free and perfectly repeatable, which is
 * why it stays. What it structurally cannot catch is a fabrication assembled
 * from the context's own words. "Restart the consumer" and "never restart the
 * consumer" share every content word and score identically. So does a correct
 * paraphrase that happens to use different vocabulary, which the same scorer
 * punishes for no reason. It is wrong in both directions and cannot be fixed by
 * tuning, because entailment is not a function of vocabulary overlap.
 *
 * <h2>What a judge costs</h2>
 *
 * Money, latency, and repeatability. A judge is a model, so two runs of the
 * same evaluation can disagree - which undermines the property an evaluation
 * harness exists to provide. Three things keep that in hand: the judge runs at
 * temperature 0, it runs offline against a fixed dataset rather than on the
 * serving path, and it is additive - the deterministic scorers still run and
 * are still reported, so a judge that starts behaving oddly is visible as a
 * divergence between the two rather than as silently different numbers.
 *
 * <h2>The failure mode to watch for</h2>
 *
 * Self-preference: a judge scores answers from its own model family higher than
 * it should. This implementation uses the CAPABLE tier, which is the tier that
 * also produces the answers being judged, so that bias is present here by
 * construction and is stated rather than hidden. The mitigation - a judge from a
 * different provider - is a configuration change away, since the model is chosen
 * in the prompt builder rather than hard-coded here.
 */
public interface LlmJudge {

    JudgeVerdict judge(String question, String answer, List<SemanticSearchResult> sources);
}
