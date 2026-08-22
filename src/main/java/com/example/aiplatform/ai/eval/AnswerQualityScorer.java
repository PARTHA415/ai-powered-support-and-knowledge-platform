package com.example.aiplatform.ai.eval;

import com.example.aiplatform.model.SemanticSearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic, mechanical scoring of retrieval and answer quality - no LLM
 * judge involved. This is a deliberate tradeoff: an LLM-as-judge can assess
 * genuine semantic groundedness and relevance far better than word-overlap
 * heuristics can, but it costs money, adds latency, and is itself
 * non-deterministic (different runs of the same eval can score differently),
 * which undermines "repeatable evaluation." These heuristics are a floor, not
 * a ceiling - see the Phase 7 docs for where they break down and what a
 * production system would add (Phase 15).
 */
final class AnswerQualityScorer {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final int MIN_WORD_LENGTH = 4;

    private AnswerQualityScorer() {
    }

    static RetrievalMetrics retrievalMetrics(List<String> retrievedTitles, List<String> expectedTitles) {
        Set<String> retrieved = new LinkedHashSet<>(retrievedTitles);
        Set<String> expected = new LinkedHashSet<>(expectedTitles);

        if (retrieved.isEmpty()) {
            return new RetrievalMetrics(expected.isEmpty() ? 1.0 : 0.0, expected.isEmpty() ? 1.0 : 0.0);
        }

        long truePositives = retrieved.stream().filter(expected::contains).count();
        double precision = (double) truePositives / retrieved.size();
        double recall = expected.isEmpty() ? 1.0 : (double) truePositives / expected.size();
        return new RetrievalMetrics(precision, recall);
    }

    /**
     * Fraction of the dataset's expected keywords/phrases that appear
     * (case-insensitively) somewhere in the answer. A crude proxy for "does
     * this answer actually cover what a correct answer should cover" - it
     * can't tell a correct paraphrase from a miss, only exact substring
     * presence, so a well-phrased-but-differently-worded correct answer can
     * score lower than it deserves.
     */
    static double relevanceScore(String answer, List<String> expectedKeywords) {
        if (expectedKeywords.isEmpty()) {
            return 1.0;
        }
        String lowerAnswer = answer.toLowerCase();
        long matched = expectedKeywords.stream().filter(keyword -> lowerAnswer.contains(keyword.toLowerCase())).count();
        return (double) matched / expectedKeywords.size();
    }

    /**
     * Fraction of the answer's distinct content words that also appear
     * somewhere in the retrieved source content - a lexical-overlap stand-in
     * for "is this claim actually supported by the context." When no
     * sources were retrieved at all, an honest "I don't know" answer is
     * vacuously grounded (there's no claim to verify), so that case scores
     * 1.0 rather than being wrongly penalized for not overlapping with
     * context that doesn't exist.
     *
     * The real limitation: this only catches vocabulary invention, not
     * logical fabrication using the *context's own words* (e.g. flipping
     * "restart the consumer" into "never restart the consumer" would score
     * identically here). True groundedness checking needs semantic entailment
     * (an NLI model or LLM judge), not word overlap - see Phase 15.
     */
    static double groundednessScore(String answer, List<SemanticSearchResult> sources) {
        if (sources.isEmpty()) {
            return 1.0;
        }
        Set<String> contextWords = sources.stream()
                .flatMap(source -> Arrays.stream(source.content().toLowerCase().split("\\W+")))
                .filter(word -> word.length() >= MIN_WORD_LENGTH)
                .collect(Collectors.toSet());

        List<String> answerWords = Arrays.stream(answer.toLowerCase().split("\\W+"))
                .filter(word -> word.length() >= MIN_WORD_LENGTH)
                .distinct()
                .toList();
        if (answerWords.isEmpty()) {
            return 1.0;
        }

        long grounded = answerWords.stream().filter(contextWords::contains).count();
        return (double) grounded / answerWords.size();
    }

    /**
     * True only if every [n] citation marker in the answer refers to a source
     * that was actually provided (1-based index into {@code sources}), and -
     * when sources were provided at all - at least one of them was cited.
     * Deliberately mechanical: this catches a hallucinated reference like
     * "[9]" when only 2 sources exist, but says nothing about whether a
     * syntactically valid citation like "[1]" actually supports the specific
     * claim next to it - that's what {@link #groundednessScore} is for, and
     * the two metrics can and do disagree (a citation can be valid while the
     * claim it's attached to is still fabricated).
     */
    static boolean citationsCorrect(String answer, List<SemanticSearchResult> sources) {
        if (sources.isEmpty()) {
            return true;
        }
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        List<Integer> citedIndexes = new ArrayList<>();
        while (matcher.find()) {
            citedIndexes.add(Integer.parseInt(matcher.group(1)));
        }
        if (citedIndexes.isEmpty()) {
            return false;
        }
        return citedIndexes.stream().allMatch(index -> index >= 1 && index <= sources.size());
    }

    /**
     * Stricter than {@link #relevanceScore}: the fraction of required facts
     * present, UNLESS any forbidden fact is also present, in which case the
     * whole score collapses to 0 regardless of how many required facts also
     * showed up. A wrong-but-confident answer isn't "partially correct" -
     * an answer that states both the right status and a wrong one is
     * actively worse than one that states neither, and averaging the two
     * away would hide that.
     */
    static double answerCorrectness(String answer, List<String> requiredFacts, List<String> forbiddenFacts) {
        String lower = answer.toLowerCase();
        boolean containsForbidden = forbiddenFacts.stream().anyMatch(fact -> lower.contains(fact.toLowerCase()));
        if (containsForbidden) {
            return 0.0;
        }
        if (requiredFacts.isEmpty()) {
            return 1.0;
        }
        long matched = requiredFacts.stream().filter(fact -> lower.contains(fact.toLowerCase())).count();
        return (double) matched / requiredFacts.size();
    }

    /**
     * 1.0 (no hallucination detected) unless the answer contains one of the
     * specific fabricated details {@code forbiddenFabrications} lists for
     * this out-of-scope question - a binary check deliberately: for a
     * question with no correct answer available at all, there is no partial
     * credit for "mostly honest, but invented one specific detail."
     */
    static double hallucinationScore(String answer, List<String> forbiddenFabrications) {
        String lower = answer.toLowerCase();
        boolean fabricated = forbiddenFabrications.stream().anyMatch(detail -> lower.contains(detail.toLowerCase()));
        return fabricated ? 0.0 : 1.0;
    }
}
