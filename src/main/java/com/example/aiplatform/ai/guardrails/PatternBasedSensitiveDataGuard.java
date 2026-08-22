package com.example.aiplatform.ai.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, regex-based output scanning - the counterpart to
 * {@link PatternBasedPromptInjectionGuard} on the way out instead of the way
 * in. Two failure modes are handled differently, matching how serious each
 * one is:
 *
 * <p><b>System-prompt leakage</b> - if the model's answer contains the
 * distinguishing phrase every system prompt in this application shares, the
 * ENTIRE answer is replaced with a fixed refusal. A model that started
 * reciting its instructions has already been successfully manipulated (an
 * injection attack got through to this point some other way, or the model
 * simply complied with a request it should have refused) - partial redaction
 * would still leak the fact that it happened and likely other fragments of
 * the prompt.
 *
 * <p><b>Secret-shaped substrings</b> (credit-card-like digit runs, SSNs,
 * API-key-shaped tokens) - these are redacted in place rather than failing
 * the whole response, because in this application they are far more likely
 * to be an accidental false positive (an order total, a tracking number)
 * than a genuine leak, and failing a legitimate answer over a false positive
 * is worse than over-redacting one substring.
 */
@Component
public class PatternBasedSensitiveDataGuard implements SensitiveDataGuard {

    private static final Logger log = LoggerFactory.getLogger(PatternBasedSensitiveDataGuard.class);

    private static final String SYSTEM_PROMPT_LEAK_MARKER = "ai assistant for a technical support and knowledge platform";
    private static final String SYSTEM_PROMPT_LEAK_REFUSAL =
            "I can't share my internal system instructions, but I'm happy to help with your support question.";

    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // Credit-card-like: 13-16 digits, optionally grouped by spaces/dashes.
            Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b"),
            // US Social Security Number.
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            // Provider-style API keys (OpenAI "sk-...", AWS access key IDs, etc).
            Pattern.compile("\\b(sk|pk)-[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bBearer\\s+[A-Za-z0-9\\-_.]{20,}\\b"),
            // Generic "secret/token/password/api_key: <value>" assignments.
            Pattern.compile("(?i)\\b(api[_-]?key|secret|password|access[_-]?token)\\s*[:=]\\s*\\S+")
    );

    @Override
    public String sanitizeOutput(String llmOutput) {
        if (llmOutput == null) {
            return null;
        }
        if (llmOutput.toLowerCase(Locale.ROOT).contains(SYSTEM_PROMPT_LEAK_MARKER)) {
            log.warn("Blocked an LLM response that appeared to recite the system prompt");
            return SYSTEM_PROMPT_LEAK_REFUSAL;
        }

        String sanitized = llmOutput;
        boolean redactedAny = false;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            if (pattern.matcher(sanitized).find()) {
                redactedAny = true;
                sanitized = pattern.matcher(sanitized).replaceAll("[REDACTED]");
            }
        }
        if (redactedAny) {
            log.warn("Redacted apparent sensitive data (card/SSN/secret-shaped text) from an LLM response");
        }
        return sanitized;
    }
}
