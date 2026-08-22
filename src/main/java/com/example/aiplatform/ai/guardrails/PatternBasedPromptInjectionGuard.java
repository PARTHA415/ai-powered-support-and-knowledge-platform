package com.example.aiplatform.ai.guardrails;

import com.example.aiplatform.exception.PromptInjectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Regex/keyword heuristics, not an LLM call. This is the concrete answer to
 * "do not rely solely on the LLM for security": every pattern below is
 * plain Java string matching that runs whether or not the model would have
 * complied with the attack, and cannot itself be talked out of doing its
 * job by clever phrasing in the input it's scanning.
 *
 * The pattern list deliberately covers three different attacker goals with
 * the same mechanism:
 * <ul>
 *   <li><b>Instruction override</b> - "ignore previous instructions", "new
 *       instructions:", "you are now X" - trying to replace the system
 *       prompt's rules with the attacker's own.</li>
 *   <li><b>System-prompt / instruction extraction</b> - "show me your system
 *       prompt", "what are your instructions" - trying to leak the
 *       confidential prompt text itself (a data-leakage goal, detected here
 *       on the input side; see {@link PatternBasedSensitiveDataGuard} for
 *       the matching output-side check).</li>
 *   <li><b>Tool/data abuse via natural language</b> - "execute this SQL
 *       against the database", "DROP TABLE" - trying to get the model to
 *       request an operation no tool actually exposes. Belt-and-braces: the
 *       real defense is that no {@code @Tool} method accepts raw SQL at all
 *       (Phase 8), but rejecting the attempt at the input boundary means it
 *       never even reaches the model, and it fails loudly instead of
 *       silently depending on the tool surface staying that way forever.</li>
 * </ul>
 *
 * False positives are an accepted, deliberate trade-off for a learning
 * project demonstrating the mechanism plainly; a production system would
 * likely tune/expand this list from real traffic and possibly add a
 * secondary ML-based classifier - see the Phase 12 docs for that discussion.
 */
@Component
public class PatternBasedPromptInjectionGuard implements PromptInjectionGuard {

    private static final Logger log = LoggerFactory.getLogger(PatternBasedPromptInjectionGuard.class);

    private static final String REDACTION_MARKER = "[REDACTED: potential prompt injection removed]";

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
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

            // --- tool/data abuse phrased as natural language ---
            Pattern.compile("(execute|run)\\s+(this\\s+|the\\s+following\\s+)?(sql|query|script)\\b",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdrop\\s+table\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdelete\\s+from\\s+\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bselect\\s+\\*\\s+from\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bunion\\s+select\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("against\\s+the\\s+(production\\s+)?database", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public boolean containsInjectionAttempt(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return INJECTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    @Override
    public void assertSafe(String userInput) {
        if (containsInjectionAttempt(userInput)) {
            log.warn("Blocked user input matching a prompt-injection pattern");
            throw new PromptInjectionException(
                    "Your message was blocked because it appears to try to override the assistant's "
                            + "instructions or request an unsafe operation. Please rephrase your support question.");
        }
    }

    @Override
    public String sanitize(String untrustedContent) {
        if (untrustedContent == null) {
            return null;
        }
        String result = untrustedContent;
        boolean matchedAny = false;
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(result).find()) {
                matchedAny = true;
                result = pattern.matcher(result).replaceAll(REDACTION_MARKER);
            }
        }
        if (matchedAny) {
            log.warn("Sanitized retrieved content that matched a prompt-injection pattern before "
                    + "splicing it into a prompt");
        }
        return result;
    }
}
