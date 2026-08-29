package com.example.aiplatform.ai.rag;

import com.example.aiplatform.observability.AiPipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, on the live serving path, that every {@code [n]} citation in an
 * answer refers to a source that was actually supplied.
 *
 * <h2>Why this exists separately from the evaluation harness</h2>
 *
 * The harness already had a citation check. It ran offline, over a fixed
 * dataset, after the fact - which meant a real user could be handed an answer
 * citing "[7]" when four excerpts were retrieved, and nothing in the request
 * path would notice. Offline evaluation tells you whether a change made things
 * better on average; it cannot protect a specific response.
 *
 * <p>This particular check is worth running live precisely because it is
 * cheap and total. It needs no model, no network and no judgement: the set of
 * valid citation indices is known exactly, so an index outside it is not a
 * borderline case to be scored, it is a fabricated reference. That combination
 * - deterministic, complete, free - is rare in an AI pipeline, and where it
 * exists it should be enforced rather than merely measured.
 *
 * <h2>Why it strips rather than rejects</h2>
 *
 * A bad citation is usually attached to an otherwise-good answer, and failing
 * the whole request would trade a small defect for a total one. Removing the
 * marker leaves the prose intact and removes the false claim of provenance -
 * which is the actual harm, because an invented "[7]" tells a reader that a
 * source they cannot see supports the sentence.
 *
 * <p>The counter-argument is real and worth stating: an answer that cited a
 * source it did not have may be unreliable in ways stripping does not fix. That
 * is what the metric is for. A sustained non-zero
 * {@code invalid_citation_stripped} rate is a prompt or retrieval problem to go
 * and fix, not a condition to be silently cleaned up forever.
 *
 * <p>The prefix is a parameter because the two grounded paths number their
 * sources differently - {@code /api/qa} uses {@code [1]}, the agent's evidence
 * block uses {@code [KB1]} - and validating one format against the other's
 * markers would strip every legitimate citation on the path it did not expect.
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    private final AiPipelineMetrics aiPipelineMetrics;

    public CitationValidator(AiPipelineMetrics aiPipelineMetrics) {
        this.aiPipelineMetrics = aiPipelineMetrics;
    }

    /**
     * @param sourceCount how many sources were given to the model; a citation
     *                    must name one of {@code 1..sourceCount}
     * @param prefix      the marker prefix, {@code ""} for {@code [1]} or
     *                    {@code "KB"} for {@code [KB1]}
     * @return the answer with any out-of-range citation markers removed
     */
    public String validate(String answer, int sourceCount, String prefix) {
        if (answer == null || answer.isBlank()) {
            return answer;
        }
        Pattern citation = Pattern.compile("\\[" + Pattern.quote(prefix) + "(\\d+)]");
        Matcher matcher = citation.matcher(answer);
        Set<String> invalid = new LinkedHashSet<>();
        StringBuilder cleaned = new StringBuilder();
        while (matcher.find()) {
            int index = parseIndex(matcher.group(1));
            if (index >= 1 && index <= sourceCount) {
                matcher.appendReplacement(cleaned, Matcher.quoteReplacement(matcher.group()));
            } else {
                invalid.add(matcher.group());
                matcher.appendReplacement(cleaned, "");
            }
        }
        matcher.appendTail(cleaned);

        if (invalid.isEmpty()) {
            return answer;
        }
        aiPipelineMetrics.recordSafetyBoundTriggered("invalid_citation_stripped");
        log.warn("Answer cited {} source(s) that were never provided ({} available) - markers removed: {}",
                invalid.size(), sourceCount, invalid);
        // Collapse the double spaces a removed marker leaves behind, so the
        // stripping is not visible as a typographic artefact in the answer.
        return cleaned.toString().replaceAll(" {2,}", " ").replaceAll(" +([.,;:])", "$1");
    }

    private static int parseIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            // A citation index too large for an int is, definitionally, out of range.
            return Integer.MAX_VALUE;
        }
    }
}
