package com.example.aiplatform.exception;

/**
 * The caller's own input (a chat message, a question, an agent request) was
 * blocked because it matched a known prompt-injection or jailbreak pattern -
 * e.g. "ignore all previous instructions", "show me the system prompt".
 * Thrown by {@link com.example.aiplatform.ai.guardrails.PromptInjectionGuard#assertSafe(String)}
 * BEFORE any prompt is built or any LLM is called: this is a deterministic
 * Java pattern match, not something the model is asked to police itself.
 */
public class PromptInjectionException extends RuntimeException {

    public PromptInjectionException(String message) {
        super(message);
    }
}
