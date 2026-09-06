package com.example.aiplatform.ai.rag;

import com.example.aiplatform.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document into chunks measured in tokens, at boundaries a human
 * would recognise.
 *
 * <h2>Why tokens instead of characters</h2>
 *
 * The previous splitter cut at a fixed character count. Every limit that
 * actually binds is denominated in tokens - the context window, the
 * prompt-size guardrail, the bill - and characters convert to tokens at a ratio
 * that varies with content. English prose runs near 4:1; a stack trace, a YAML
 * block, or a table of error codes runs closer to 2:1. A fixed 800-character
 * chunk was therefore anywhere from 200 to 400 tokens, and the densest chunks
 * in a technical corpus - the ones most worth retrieving - were reliably the
 * biggest. Retrieving five of them could double a prompt size nobody had
 * budgeted for. Counting the real unit removes the variance rather than
 * estimating around it.
 *
 * <h2>Why boundaries instead of a hard cut</h2>
 *
 * A chunk is what gets embedded, and an embedding of a fragment that starts
 * mid-sentence is an embedding of something nobody would ever search for. This
 * splitter prefers to cut at a blank line, then at a sentence end, and only
 * falls back to the token limit when neither is available within the window. It
 * is a cheap approximation of semantic chunking - real semantic chunking scores
 * adjacent sentences for topical similarity and cuts where the score drops,
 * which needs an embedding call per sentence at ingestion time and is a
 * reasonable next step, not a free one.
 *
 * <h2>Overlap</h2>
 *
 * Consecutive chunks share their last {@code chunkOverlapTokens}. Without
 * overlap, a fact that straddles a cut is present in neither chunk in a
 * retrievable form: the first half embeds as a truncated thought, the second as
 * a fragment with no subject. Overlap is duplicated storage and duplicated
 * embedding cost, deliberately paid, because the alternative is a fact that
 * silently cannot be found.
 *
 * <h2>The tokenizer</h2>
 *
 * {@link JTokkitTokenCountEstimator} implements the BPE tokenizer OpenAI models
 * use. Another provider's tokenizer will differ - typically by a few percent,
 * not by a factor - so the counts here are exact for the default configuration
 * and a close estimate otherwise. That is a far better position than
 * characters-divided-by-four, which was an estimate of an estimate, and the
 * abstraction ({@link TokenCountEstimator}) is Spring AI's portable one rather
 * than a provider type.
 */
@Component
public class TokenAwareChunker {

    private static final Logger log = LoggerFactory.getLogger(TokenAwareChunker.class);

    /** A blank line - the strongest structural boundary in a technical document. */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    /** Sentence end followed by whitespace. Deliberately simple; abbreviations occasionally fool it. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s)");

    /**
     * How far back from the token limit a boundary may be found before the
     * splitter gives up and cuts at the limit. Without a floor, a document with
     * one paragraph break near the start would produce a stream of tiny chunks:
     * each cut would take the earliest boundary it could see, and every chunk
     * would be a fraction of its budget.
     */
    private static final double MIN_BOUNDARY_FRACTION = 0.5;

    private final RagProperties ragProperties;
    private final TokenCountEstimator tokenCountEstimator;

    /**
     * The constructor Spring uses. {@code @Autowired} is required, not
     * decorative: the test-only constructor below makes this a multi-constructor
     * class, and with two candidates and no annotation Spring cannot choose
     * either - it falls back to a no-arg constructor that does not exist and
     * fails context startup with "No default constructor found".
     */
    @Autowired
    public TokenAwareChunker(RagProperties ragProperties) {
        this(ragProperties, new JTokkitTokenCountEstimator());
    }

    /** Test seam: lets a unit test supply a deterministic token counter. */
    TokenAwareChunker(RagProperties ragProperties, TokenCountEstimator tokenCountEstimator) {
        this.ragProperties = ragProperties;
        this.tokenCountEstimator = tokenCountEstimator;
    }

    public int countTokens(String text) {
        return tokenCountEstimator.estimate(text);
    }

    public List<String> split(String content) {
        return split(content, ragProperties.chunkTokens(), ragProperties.chunkOverlapTokens());
    }

    List<String> split(String content, int chunkTokens, int overlapTokens) {
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        int targetTokens = Math.max(1, chunkTokens);
        int overlap = Math.max(0, Math.min(overlapTokens, targetTokens - 1));

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = endOfChunk(text, start, targetTokens);
            String chunk = text.substring(start, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }
            int next = start + overlapAdjustedAdvance(text, start, end, overlap);
            // Never fail to advance: a pathological boundary choice that left
            // next <= start would loop forever, and an infinite loop during
            // ingestion is a far worse outcome than one slightly-small chunk.
            start = Math.max(next, start + 1);
        }
        log.debug("Split {} characters into {} chunk(s) of ~{} tokens with ~{} token overlap",
                text.length(), chunks.size(), targetTokens, overlap);
        return chunks;
    }

    /**
     * The character offset where this chunk should end: as much text as fits in
     * the token budget, walked back to the nearest structural boundary.
     */
    private int endOfChunk(String text, int start, int targetTokens) {
        int hardEnd = characterOffsetForTokens(text, start, targetTokens);
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int minimumEnd = start + (int) ((hardEnd - start) * MIN_BOUNDARY_FRACTION);

        int paragraphBoundary = lastMatchEnd(PARAGRAPH_BREAK, text, start, hardEnd);
        if (paragraphBoundary > minimumEnd) {
            return paragraphBoundary;
        }
        int sentenceBoundary = lastMatchEnd(SENTENCE_END, text, start, hardEnd);
        if (sentenceBoundary > minimumEnd) {
            return sentenceBoundary;
        }
        int whitespace = text.lastIndexOf(' ', hardEnd);
        return whitespace > minimumEnd ? whitespace : hardEnd;
    }

    /**
     * Binary search for the longest prefix from {@code start} whose token count
     * fits the budget.
     *
     * <p>Tokenizing is not free, so this deliberately does not walk forward
     * character by character. It seeds from the observed characters-per-token
     * ratio of this document's own text and then narrows, converging in a
     * handful of estimate calls whether the content is prose or a stack trace -
     * the two cases a fixed ratio gets wrong in opposite directions.
     */
    private int characterOffsetForTokens(String text, int start, int targetTokens) {
        int remaining = text.length() - start;
        int totalTokens = Math.max(1, countTokens(text.substring(start)));
        double charsPerToken = (double) remaining / totalTokens;

        int low = 0;
        int high = remaining;
        int best = 0;
        int guess = Math.min(remaining, Math.max(1, (int) (targetTokens * charsPerToken)));
        while (low <= high) {
            int candidate = guess > 0 ? Math.max(low, Math.min(guess, high)) : (low + high) / 2;
            guess = 0;
            int tokens = countTokens(text.substring(start, start + candidate));
            if (tokens <= targetTokens) {
                best = candidate;
                low = candidate + 1;
            } else {
                high = candidate - 1;
            }
        }
        return start + Math.max(1, best);
    }

    /**
     * How far to advance the cursor: the chunk length, minus roughly
     * {@code overlapTokens} worth of characters so the next chunk re-includes
     * this one's tail.
     */
    private int overlapAdjustedAdvance(String text, int start, int end, int overlapTokens) {
        int length = end - start;
        if (overlapTokens == 0) {
            return length;
        }
        int chunkTokenCount = Math.max(1, countTokens(text.substring(start, end)));
        double charsPerToken = (double) length / chunkTokenCount;
        int overlapChars = Math.min(length - 1, (int) (overlapTokens * charsPerToken));
        return Math.max(1, length - overlapChars);
    }

    private static int lastMatchEnd(Pattern pattern, String text, int start, int limit) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(start, limit);
        int lastEnd = -1;
        while (matcher.find()) {
            lastEnd = matcher.end();
        }
        return lastEnd;
    }
}
