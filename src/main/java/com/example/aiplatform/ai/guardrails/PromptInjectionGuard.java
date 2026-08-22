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
     * True if the given text contains a recognizable injection, jailbreak,
     * or unsafe-operation-request pattern. Never throws; used for
     * non-blocking checks (e.g. flagging a document at ingestion time).
     */
    boolean containsInjectionAttempt(String text);

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
