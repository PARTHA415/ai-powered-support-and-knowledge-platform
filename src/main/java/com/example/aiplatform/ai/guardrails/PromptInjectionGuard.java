package com.example.aiplatform.ai.guardrails;

/**
 * Deterministic, pattern-based defense against prompt injection - never an
 * LLM call asking "is this safe?" (an LLM cannot be trusted to police
 * itself: it is exactly the thing being attacked). Two distinct threats get
 * two distinct methods, because they call for different responses:
 *
 * <p><b>Direct injection</b> - the live caller's own message tries to
 * override the system prompt ("ignore all previous instructions"). This is
 * an attack on THIS request, from a caller we can simply refuse. Use
 * {@link #assertSafe(String)}, which throws and stops the request before any
 * prompt is built or any LLM is called.
 *
 * <p><b>Indirect injection</b> - a knowledge-base document or a tool result
 * contains adversarial instructions, discovered later and spliced into a
 * prompt as "trusted" context. The attacker here is not the live caller (who
 * may be an entirely innocent customer asking an unrelated question), so
 * refusing the whole request is the wrong response - it would let one
 * poisoned document deny service to every legitimate question that happens
 * to retrieve it. Use {@link #sanitize(String)}, which neutralizes only the
 * offending text and lets the rest of the content through.
 */
public interface PromptInjectionGuard {

    /**
     * True if the given text contains a recognizable injection or jailbreak
     * pattern - meaning {@link #assertSafe(String)} WOULD reject it. Never
     * throws; used for non-blocking checks (flagging a document at ingestion
     * time) and by the safety evaluation, which relies on this answering the
     * same question the blocking path asks.
     */
    boolean containsInjectionAttempt(String text);

    /**
     * True if the text contains SQL-shaped language with no imperative aimed
     * at the assistant - {@code DROP TABLE}, {@code SELECT * FROM}, and so on.
     *
     * <p>Deliberately separate from {@link #containsInjectionAttempt(String)}
     * and deliberately non-blocking. On a technical support platform this is
     * the subject matter, not an attack: refusing it rejected legitimate
     * questions, and redacting it from retrieved documents quietly gutted the
     * SQL runbooks the knowledge base exists to serve. The real defense is that
     * no tool accepts SQL, so there is nothing such text could reach. Worth a
     * log line; not worth a refusal.
     */
    boolean containsSuspiciousDatabaseLanguage(String text);

    /**
     * Throws {@link com.example.aiplatform.exception.PromptInjectionException}
     * if the caller's own input looks like an injection attempt. Call this
     * on live user input, before building any prompt.
     */
    void assertSafe(String userInput);

    /**
     * Redacts any injection-pattern matches found in untrusted content
     * (retrieved document chunks, tool results) and returns the rest
     * unchanged. Never throws - this is content the application itself
     * retrieved, not something the live caller can be denied service over.
     */
    String sanitize(String untrustedContent);
}
