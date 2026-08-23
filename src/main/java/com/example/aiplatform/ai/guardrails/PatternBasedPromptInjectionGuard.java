package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.exception.PromptInjectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Regex/keyword heuristics, not an LLM call. This is the concrete answer to
 * "do not rely solely on the LLM for security": every pattern below is plain
 * Java string matching that runs whether or not the model would have complied
 * with the attack, and cannot itself be talked out of doing its job by clever
 * phrasing in the input it's scanning.
 *
 * <h2>Two lists, because two very different costs</h2>
 *
 * The original single list treated "ignore all previous instructions" and
 * "delete from orders" as the same kind of evidence, and hard-rejected both.
 * On a <em>technical</em> support platform that second class is not an attack,
 * it is the subject matter. A user asking
 * <em>"my {@code DELETE FROM orders} migration is hanging, how do I debug
 * it?"</em> received an HTTP 400 telling them to rephrase their support
 * question, and a SQL troubleshooting runbook retrieved from the knowledge base
 * had its most useful lines replaced with redaction markers before the model
 * ever saw it - degrading answers with no user-visible signal that anything had
 * been removed.
 *
 * <p><b>{@link #BLOCKING_PATTERNS}</b> - text that only makes sense as an
 * instruction aimed at the assistant: overriding its rules, extracting its
 * prompt, or directing it to run a statement against a database. These reject
 * the request and redact retrieved content.
 *
 * <p><b>{@link #SUSPICIOUS_DATABASE_PATTERNS}</b> - SQL-shaped text with no
 * imperative aimed at the assistant. Recorded, never acted on. A support
 * platform for engineers must be able to discuss {@code DROP TABLE} without
 * refusing to answer.
 *
 * <p>The SQL entries that remain in the blocking list are narrowed to require
 * BOTH an imperative and a target ("execute this SQL <b>against</b> the
 * production database"), which is what separates an instruction to the
 * assistant from a developer describing their own query. Note that even these
 * are belt-and-braces: the real defense is that no {@code @Tool} method accepts
 * SQL at all, so there is nothing for such an instruction to reach.
 */
@Component
public class PatternBasedPromptInjectionGuard implements PromptInjectionGuard {

    private static final Logger log = LoggerFactory.getLogger(PatternBasedPromptInjectionGuard.class);

    private static final String REDACTION_MARKER = "[REDACTED: potential prompt injection removed]";
    private static final String FENCE_ESCAPE_MARKER = "[REDACTED: fence tag removed]";

    /**
     * Opening or closing tags for the fences that wrap untrusted content in the
     * prompt templates (prompts/rag-user.st, prompts/agent-final-user.st). Kept
     * in sync with those templates by name.
     */
    private static final Pattern FENCE_TAG_PATTERN = Pattern.compile(
            "</?\\s*(knowledge_base_excerpts|gathered_evidence)\\s*>", Pattern.CASE_INSENSITIVE);

    /**
     * Rejects the request, and redacts matches from retrieved content. Every
     * entry here is phrased AT the assistant - there is no legitimate support
     * question that contains one.
     */
    private static final List<Pattern> BLOCKING_PATTERNS = List.of(
            // --- instruction override (direct injection) ---
            Pattern.compile("ignore\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+instructions",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+|everything\\s+|your\\s+)?(previous\\s+)?instructions",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("new\\s+instructions\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("override\\s+(your\\s+)?(instructions|system\\s*prompt|rules|guardrails)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend\\s+(that\\s+)?you\\s+(are|have)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(an?\\s+)?(unrestricted|jailbroken|dan|evil)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("developer\\s*mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjailbreak\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdan\\s+mode\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("bypass\\s+(your\\s+)?(guardrails|restrictions|filters|security|authorization)",
                    Pattern.CASE_INSENSITIVE),

            // --- system-prompt / instruction extraction ---
            Pattern.compile("(reveal|show|print|display|leak)\\s+(me\\s+)?(the\\s+|your\\s+)?(system\\s*)?prompt",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("what\\s+(are|is)\\s+your\\s+(system\\s+)?(instructions|prompt)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("repeat\\s+(the\\s+|your\\s+)?(words|text|instructions)\\s+above",
                    Pattern.CASE_INSENSITIVE),

            // --- database operation directed AT the assistant ---
            // Requires an imperative AND a target. "Execute SQL against the
            // production database" matches; "how do I run this query faster?"
            // and "why is my DELETE FROM orders slow?" deliberately do not.
            Pattern.compile("(execute|run|perform)\\s+(this\\s+|the\\s+following\\s+|arbitrary\\s+|raw\\s+)?"
                            + "(sql|quer(y|ies)|statements?|scripts?)\\s+(against|on|in|directly\\s+against)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(execute|run)\\s+(this\\s+|the\\s+following\\s+)?(sql|query)\\s+for\\s+me",
                    Pattern.CASE_INSENSITIVE)
    );

    /**
     * Observed and logged, never blocked or redacted. SQL-shaped text is normal
     * subject matter here; its presence is weak evidence worth a log line and
     * nothing more. Kept separate so the signal is not lost while the
     * false-positive cost is.
     */
    private static final List<Pattern> SUSPICIOUS_DATABASE_PATTERNS = List.of(
            Pattern.compile("\\bdrop\\s+table\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdelete\\s+from\\s+\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bselect\\s+\\*\\s+from\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bunion\\s+select\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btruncate\\s+table\\b", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public boolean containsInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return BLOCKING_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    @Override
    public boolean containsSuspiciousDatabaseLanguage(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return SUSPICIOUS_DATABASE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    @Override
    public void assertSafe(String userInput) {
        if (containsInjectionAttempt(userInput)) {
            log.warn("Blocked user input matching a prompt-injection pattern");
            throw new PromptInjectionException(
                    "Your message was blocked because it appears to try to override the assistant's "
                            + "instructions or request an unsafe operation. Please rephrase your support question.");
        }
        if (containsSuspiciousDatabaseLanguage(userInput)) {
            // Logged, deliberately not blocked - see the class comment. On a
            // technical support platform this is the subject matter.
            log.info("User input contains SQL-shaped text; allowing it - no tool accepts SQL");
        }
    }

    /**
     * Redacts only {@link #BLOCKING_PATTERNS}. SQL-shaped text in a retrieved
     * document is documentation, and blanking it out was silently destroying
     * the usefulness of exactly the runbooks this knowledge base exists to
     * serve.
     *
     * <p>Also neutralizes any attempt to close the untrusted-content fence.
     * Retrieved text is now wrapped in {@code <knowledge_base_excerpts>} /
     * {@code <gathered_evidence>} tags that tell the model everything inside is
     * data rather than instructions - so a document containing its own closing
     * tag could otherwise break out of the fence and have whatever followed
     * read as trusted prompt text. The structural boundary only holds if the
     * content cannot forge the boundary marker, which makes this escaping part
     * of the defense rather than cosmetic.
     */
    @Override
    public String sanitize(String untrustedContent) {
        if (untrustedContent == null) {
            return null;
        }
        String result = untrustedContent;
        boolean matchedAny = false;
        for (Pattern pattern : BLOCKING_PATTERNS) {
            if (pattern.matcher(result).find()) {
                matchedAny = true;
                result = pattern.matcher(result).replaceAll(REDACTION_MARKER);
            }
        }
        if (FENCE_TAG_PATTERN.matcher(result).find()) {
            matchedAny = true;
            result = FENCE_TAG_PATTERN.matcher(result).replaceAll(FENCE_ESCAPE_MARKER);
            log.warn("Retrieved content contained an untrusted-content fence tag; neutralized it to "
                    + "prevent the content from escaping its delimiter");
        }
        if (matchedAny) {
            log.warn("Sanitized retrieved content that matched a prompt-injection pattern before "
                    + "splicing it into a prompt");
        }
        return result;
    }
}
