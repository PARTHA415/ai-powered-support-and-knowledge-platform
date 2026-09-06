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
import java.util.stream.Stream;

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

    /** Splits a dataset fact into the alternative wordings it accepts - see {@link #mentions}. */
    private static final Pattern ALTERNATIVE_SEPARATOR = Pattern.compile("\\|");

    /**
     * Words that carry no claim, so their presence or absence says nothing
     * about whether an answer is grounded - see {@link #groundednessScore}.
     * Only words of {@link #MIN_WORD_LENGTH} or longer need listing; shorter
     * ones never reach the comparison. Stored as stems, and looked up as
     * stems, so one entry covers a word's inflections ("cause" also removes
     * "causes" and "causing").
     *
     * <p>The line to hold: generic English, never domain vocabulary. Adding
     * "consumer" or "timeout" here would make the metric congratulate itself -
     * every word that could evidence a fabrication would have been excluded
     * from the check before it ran.
     */
    private static final Set<String> STOPWORD_STEMS = Stream.of(
            "about", "address", "after", "again", "also", "another", "because", "been", "before", "being",
            "both", "cannot", "cause", "come", "consider", "could", "does", "done", "down", "during",
            "each", "else", "ensure", "even", "every", "find", "first", "following", "from", "further",
            "give", "given", "good", "have", "help", "here", "however", "indeed", "into", "issue",
            "just", "kind", "know", "less", "like", "likely", "made", "make", "many", "more",
            "might", "most", "much", "must", "need", "next", "note", "occur", "often", "only", "other",
            "over", "pace", "part", "perhaps", "please", "possible", "problem", "provide", "rather", "really",
            "same", "several", "should", "similar", "simply", "since", "some", "something", "such", "sure",
            "take", "than", "that", "their", "them", "then", "there", "these", "they", "thing",
            "this", "those", "through", "thus", "time", "together", "toward", "under", "until", "upon",
            "used", "using", "very", "want", "well", "were", "what", "when", "where", "whether",
            "which", "while", "will", "with", "within", "without", "would", "your"
    ).map(AnswerQualityScorer::stem).collect(Collectors.toUnmodifiableSet());

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
     *
     * <p><b>Only content words count, and they are compared as stems.</b> The
     * first version of this compared raw words, and it scored a correct,
     * well-sourced Kafka answer at 0.5 against a 0.7 bar. Of the 19 words it
     * called unsupported, not one was a technical claim - they were
     * {@code address}, {@code consider}, {@code indeed}, {@code issue},
     * {@code might}, {@code problem}, {@code this}, {@code which} and the like,
     * plus three that WERE in the source in another form ({@code grow} against
     * the source's "grows", {@code raising} against "Raise", {@code adding}
     * against "add"). Every domain token - poll, records, partitions,
     * consumers, producer - was already present. The metric was measuring
     * prose style, and punishing the model for writing a sentence instead of
     * copying a runbook fragment.
     *
     * <p>That mattered for a reason beyond the one score: a case that is red on
     * every run cannot detect anything. Had the answer later begun genuinely
     * fabricating, the score would have slid from 0.5 to 0.3 and the report
     * would still just have said "fail" - the case had no signal left to give.
     * A permanently red test is a dead test.
     *
     * <p>Stemming is deliberately crude (suffix stripping, not Porter) and both
     * sides go through the identical pipeline, so the failure mode is two
     * distinct words colliding on one stem - which inflates a score slightly -
     * rather than the same word being counted as unsupported twice over.
     */
    static double groundednessScore(String answer, List<SemanticSearchResult> sources) {
        if (sources.isEmpty()) {
            return 1.0;
        }
        Set<String> contextStems = sources.stream()
                .flatMap(source -> contentStems(source.content()))
                .collect(Collectors.toSet());

        List<String> answerStems = contentStems(answer).distinct().toList();
        // An answer made entirely of stopwords asserts nothing to verify.
        if (answerStems.isEmpty()) {
            return 1.0;
        }

        long grounded = answerStems.stream().filter(contextStems::contains).count();
        return (double) grounded / answerStems.size();
    }

    /**
     * The claim-bearing stems of a passage: stemmed so inflections match, long
     * enough to be unambiguous, and with the function and filler words removed.
     * Both the answer and the context are reduced this way before they are
     * compared.
     *
     * <p>Order matters, and getting it wrong is subtle. Filtering by length
     * BEFORE stemming drops the runbook's three-letter "add" from the context
     * while keeping the answer's "adding" - which then stems to "add" and can
     * never match anything, penalising the answer for a word the source
     * actually contains. Stemming first and measuring the stem keeps the two
     * sides symmetric: a token either survives in both or in neither.
     */
    private static Stream<String> contentStems(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .map(AnswerQualityScorer::stem)
                .filter(stem -> stem.length() >= MIN_WORD_LENGTH)
                .filter(stem -> !STOPWORD_STEMS.contains(stem));
    }

    /**
     * Crude suffix stripping - enough to make "grows"/"grow",
     * "raising"/"raise" and "adding"/"add" compare equal, and nothing more.
     * The trailing "e" is dropped last so that a stripped "raising" ("rais")
     * and a bare "raise" ("rais") land on the same stem.
     *
     * <p>A real stemmer (Porter, Snowball) would be more accurate, but it is a
     * dependency and a behaviour change in a scorer whose whole value is being
     * boringly reproducible across runs. The length guards keep short words
     * intact rather than shredding them into ambiguous fragments.
     */
    private static String stem(String word) {
        String stem = word;
        if (stem.endsWith("ing") && stem.length() >= 6) {
            stem = stem.substring(0, stem.length() - 3);
        } else if (stem.endsWith("ed") && stem.length() >= 5) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.endsWith("ly") && stem.length() >= 5) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.endsWith("es") && stem.length() >= 5) {
            stem = stem.substring(0, stem.length() - 2);
        } else if (stem.endsWith("s") && !stem.endsWith("ss") && stem.length() >= 4) {
            stem = stem.substring(0, stem.length() - 1);
        }
        if (stem.endsWith("e") && stem.length() >= 4) {
            stem = stem.substring(0, stem.length() - 1);
        }
        return stem;
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
        boolean containsForbidden = forbiddenFacts.stream().anyMatch(fact -> mentions(answer, fact, true));
        if (containsForbidden) {
            return 0.0;
        }
        if (requiredFacts.isEmpty()) {
            return 1.0;
        }
        long matched = requiredFacts.stream().filter(fact -> mentions(answer, fact, false)).count();
        return (double) matched / requiredFacts.size();
    }

    /**
     * Does {@code answer} actually assert {@code fact}?
     *
     * <p>Replaces a plain {@code answer.toLowerCase().contains(fact.toLowerCase())},
     * which failed a correct answer in a way worth recording. For the payment
     * case the required fact is {@code PENDING} and {@code PAID} is forbidden;
     * the assistant answered:
     *
     * <pre>The payment status for order ORD-1002 is "PENDING", and no amount
     * has been paid yet.</pre>
     *
     * That is right in both respects, but lowercased substring matching found
     * "paid" inside the ordinary English "has been paid yet" and collapsed the
     * score to 0. The eval reported a correct answer as wrong, every run.
     *
     * <p><b>Word boundaries</b> apply to both kinds of fact, so a fact never
     * matches inside a longer word ({@code PAID} must not fire on "prepaid").
     *
     * <p><b>Casing is treated asymmetrically</b>, because the two kinds of fact
     * fail in opposite directions:
     *
     * <ul>
     *   <li><b>Forbidden</b> facts ({@code strictCase = true}) match
     *       case-sensitively when written ALL-CAPS. The datasets write status
     *       values as enum tokens ({@code PAID}, {@code PENDING}), and the
     *       casing is exactly what separates a value quoted from business data
     *       from the same letters used as ordinary prose. Boundaries alone do
     *       not help here: "paid" in "has been paid" IS a standalone word, so
     *       {@code \bpaid\b} still matches it.</li>
     *   <li><b>Required</b> facts ({@code strictCase = false}) always match
     *       case-insensitively. An assistant writing {@code The status is
     *       "Shipped."} has stated the required fact {@code SHIPPED}; demanding
     *       the model preserve upstream enum casing in prose fails correct
     *       answers. A first attempt at this fix applied strict casing to both
     *       and broke exactly that case.</li>
     * </ul>
     *
     * <p>The asymmetry follows the cost of each error: a false positive on a
     * forbidden fact zeroes an otherwise correct answer, while being permissive
     * about the casing of a required fact costs nothing - the fact is either
     * stated or it is not.
     *
     * <p>Mixed- or lower-case facts (e.g. {@code "out of stock"}) are always
     * case-insensitive; they are phrases rather than enum values.
     *
     * <p><b>Alternatives</b>: a fact may list several acceptable wordings
     * separated by {@code |}, and matches if ANY of them is stated - so
     * {@code "out of stock|0 units"} is satisfied by either phrasing. This
     * exists because a required fact is a claim, not a script: the inventory
     * case demanded the literal phrase "out of stock" while forbidding "in
     * stock", and the assistant answered "There are currently 0 units of
     * product PROD-2002 in stock" - factually right, scored 0 twice over
     * (the required phrase was absent, and the forbidden one matched inside
     * the correct answer's own wording). The eval reported a model failure
     * that was really a phrasing mismatch, and did so intermittently, which
     * is the worst kind: a red case that goes green on a re-run teaches
     * people to ignore the harness.
     *
     * <p>Each alternative is quoted and boundary-matched independently, so
     * {@code |} is a separator here and never regex syntax the dataset can
     * inject. The casing rule above is applied per alternative.
     */
    private static boolean mentions(String answer, String fact, boolean strictCase) {
        return ALTERNATIVE_SEPARATOR.splitAsStream(fact)
                .map(String::strip)
                .filter(alternative -> !alternative.isEmpty())
                .anyMatch(alternative -> states(answer, alternative, strictCase));
    }

    /**
     * Whether one literal wording - no alternatives left to expand - is
     * asserted by the answer, under the boundary and casing rules described
     * on {@link #mentions}.
     */
    private static boolean states(String answer, String literal, boolean strictCase) {
        // "ALL-CAPS" means at least one letter and no lowercase ones - so "42"
        // is not treated as a case-sensitive enum token.
        boolean enumLikeToken = literal.chars().anyMatch(Character::isUpperCase)
                && literal.chars().noneMatch(Character::isLowerCase);
        int flags = (strictCase && enumLikeToken) ? 0 : Pattern.CASE_INSENSITIVE;
        return Pattern.compile("\\b" + Pattern.quote(literal) + "\\b", flags)
                .matcher(answer)
                .find();
    }

    /**
     * 1.0 (no hallucination detected) unless the answer contains one of the
     * specific fabricated details {@code forbiddenFabrications} lists for
     * this out-of-scope question - a binary check deliberately: for a
     * question with no correct answer available at all, there is no partial
     * credit for "mostly honest, but invented one specific detail."
     */
    static double hallucinationScore(String answer, List<String> forbiddenFabrications) {
        // Same matching rule as answerCorrectness - see mentions(). This check
        // had the identical substring flaw, and a false "hallucination detected"
        // is the more damaging direction of error: it accuses an honest answer.
        boolean fabricated = forbiddenFabrications.stream().anyMatch(detail -> mentions(answer, detail, true));
        return fabricated ? 0.0 : 1.0;
    }
}
