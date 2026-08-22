package com.example.aiplatform.ai.guardrails;

/**
 * Output-side guardrail: scans text the LLM produced - never text a user
 * submitted - for sensitive-information leakage before it leaves the
 * application. Applied at the single choke point every LLM response flows
 * through ({@link com.example.aiplatform.ai.llm.LlmClientService}), so every
 * endpoint (chat, RAG, tool-calling, agent) is covered automatically,
 * exactly the way Phase 11's tool authorization covers the agent workflow
 * automatically because it is the same code path.
 */
public interface SensitiveDataGuard {

    /**
     * Returns a safe version of {@code llmOutput}: secret-shaped substrings
     * (credit-card-like digit runs, SSNs, API-key-shaped tokens) redacted in
     * place, or - if the output appears to be reciting the system prompt
     * itself - replaced entirely with a fixed refusal message.
     */
    String sanitizeOutput(String llmOutput);
}
