package com.example.aiplatform.exception;

/**
 * A prompt about to be sent to the LLM exceeds the configured token budget
 * (app.guardrails.max-prompt-tokens). Thrown before the provider is ever
 * called - see {@link com.example.aiplatform.ai.llm.SpringAiLlmClientService} -
 * so an oversized document, an inflated conversation history, or a
 * maliciously padded input cannot silently balloon cost, latency, or exceed
 * the model's real context window.
 */
public class PromptTooLargeException extends RuntimeException {

    public PromptTooLargeException(String message) {
        super(message);
    }
}
