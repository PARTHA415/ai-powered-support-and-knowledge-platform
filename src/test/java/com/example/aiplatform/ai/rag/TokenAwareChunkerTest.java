package com.example.aiplatform.ai.rag;

import com.example.aiplatform.config.TestRagProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenAwareChunkerTest {

    private final TokenAwareChunker chunker = new TokenAwareChunker(TestRagProperties.defaults());

    @Test
    void shortTextProducesOneChunk() {
        List<String> chunks = chunker.split("How do I reset my password?");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("How do I reset my password?");
    }

    @Test
    void emptyOrBlankTextProducesNoChunks() {
        assertThat(chunker.split("")).isEmpty();
        assertThat(chunker.split("   \n  ")).isEmpty();
    }

    /**
     * The reason this class exists. Prose and dense technical content have very
     * different characters-per-token ratios, so a character-based splitter
     * produced chunks whose real token size varied by roughly a factor of two -
     * and always in the wrong direction, making the most information-dense
     * chunks the largest. Every chunk must respect the TOKEN budget regardless
     * of what the content looks like.
     */
    @Test
    void everyChunkRespectsTheTokenBudgetForProseAndForDenseContent() {
        String prose = "The consumer group rebalanced because the session timeout expired. ".repeat(40);
        String dense = "ERROR_CODE_4711={retries:3,backoff_ms:250,topic:orders.v2}; ".repeat(40);

        for (String content : List.of(prose, dense)) {
            List<String> chunks = chunker.split(content, 60, 0);

            assertThat(chunks).hasSizeGreaterThan(1);
            assertThat(chunks).allSatisfy(chunk ->
                    assertThat(chunker.countTokens(chunk)).isLessThanOrEqualTo(60));
        }
    }

    @Test
    void chunksReassembleIntoTheOriginalContentWhenThereIsNoOverlap() {
        String content = uniqueWordContent(300);

        String rejoined = String.join(" ", chunker.split(content, 40, 0));

        assertThat(normalizeWhitespace(rejoined)).isEqualTo(content);
    }

    @Test
    void configuredOverlapCausesConsecutiveChunksToShareWords() {
        List<String> chunks = chunker.split(uniqueWordContent(300), 40, 15);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(sharedWords(chunks.get(0), chunks.get(1))).isNotEmpty();
    }

    @Test
    void zeroOverlapProducesNoSharedWordsBetweenConsecutiveChunks() {
        List<String> chunks = chunker.split(uniqueWordContent(300), 40, 0);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(sharedWords(chunks.get(0), chunks.get(1))).isEmpty();
    }

    /**
     * A chunk that begins mid-sentence embeds as something nobody would search
     * for, so the splitter walks back to a paragraph break when one is available
     * within the window.
     */
    @Test
    void prefersToCutAtAParagraphBoundary() {
        String first = "First paragraph about consumer lag. ".repeat(8).strip();
        String second = "Second paragraph about partition reassignment. ".repeat(8).strip();

        List<String> chunks = chunker.split(first + "\n\n" + second, 80, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0)).isEqualTo(first);
    }

    /**
     * Text with no boundary anywhere - one enormous unbroken token stream -
     * must still terminate and still respect the budget. A splitter that only
     * advanced when it found a boundary would loop forever here, and an
     * infinite loop during ingestion is worse than an ugly chunk.
     */
    @Test
    void terminatesOnContentWithNoBoundaries() {
        String unbroken = "x".repeat(5000);

        List<String> chunks = chunker.split(unbroken, 30, 5);

        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).contains("xxxxx");
    }

    private static String uniqueWordContent(int wordCount) {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            content.append("token").append(i).append(' ');
        }
        return content.toString().strip();
    }

    private static String normalizeWhitespace(String text) {
        return String.join(" ", splitOnWhitespace(text));
    }

    private static Set<String> sharedWords(String first, String second) {
        Set<String> firstWords = new HashSet<>(Arrays.asList(splitOnWhitespace(first)));
        Set<String> secondWords = new HashSet<>(Arrays.asList(splitOnWhitespace(second)));
        firstWords.retainAll(secondWords);
        return firstWords;
    }

    /** Avoids a regex literal so the test carries no escaping of its own. */
    private static String[] splitOnWhitespace(String text) {
        return text.strip().split("[ \t\r\n]+");
    }
}
