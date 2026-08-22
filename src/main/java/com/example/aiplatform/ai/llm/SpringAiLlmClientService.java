package com.example.aiplatform.ai.llm;

import com.example.aiplatform.ai.guardrails.SensitiveDataGuard;
import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.exception.PromptTooLargeException;
import com.example.aiplatform.observability.AiPipelineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

/**
 * Every LLM call in this application - chat, structured output, RAG, tool
 * calling, agent planning, agent finalization - flows through exactly this
 * class, because they all go through the {@link LlmClientService} seam
 * rather than touching Spring AI's {@code ChatClient} directly. That makes
 * this the single choke point for the two Phase 12 guardrails that need to
 * apply uniformly to every LLM interaction regardless of which business
 * feature triggered it: a hard prompt-size ceiling before the call, and
 * sensitive-data/system-prompt-leak scrubbing on the response after it - the
 * same "enforced once, inherited everywhere" property Phase 11 already
 * established for tool authorization.
 */
@Service
public class SpringAiLlmClientService implements LlmClientService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClientService.class);

    /** Rough heuristic, not a real tokenizer - see the Phase 12 docs for why. */
    private static final int CHARS_PER_ESTIMATED_TOKEN = 4;

    private final ChatClient chatClient;
    private final GuardrailProperties guardrailProperties;
    private final SensitiveDataGuard sensitiveDataGuard;
    private final AiPipelineMetrics aiPipelineMetrics;

    public SpringAiLlmClientService(ChatClient.Builder chatClientBuilder,
                                     GuardrailProperties guardrailProperties,
                                     SensitiveDataGuard sensitiveDataGuard,
                                     AiPipelineMetrics aiPipelineMetrics) {
        this.chatClient = chatClientBuilder.build();
        this.guardrailProperties = guardrailProperties;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.aiPipelineMetrics = aiPipelineMetrics;
    }

    @Override
    public String generate(Prompt prompt) {
        assertWithinTokenBudget(prompt);
        log.debug("Sending prompt to LLM ({} messages)", prompt.getInstructions().size());
        try {
            String response = chatClient.prompt(prompt)
                    .call()
                    .content();
            log.debug("Received LLM response ({} chars)", response == null ? 0 : response.length());
            return sensitiveDataGuard.sanitizeOutput(response);
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new LlmIntegrationException("Failed to get a response from the LLM", e);
        }
    }

    @Override
    public String generateWithTools(Prompt prompt, Object... tools) {
        assertWithinTokenBudget(prompt);
        log.debug("Sending prompt to LLM with {} tool object(s) ({} messages)",
                tools.length, prompt.getInstructions().size());
        try {
            String response = chatClient.prompt(prompt)
                    .tools(tools)
                    .call()
                    .content();
            log.debug("Received LLM response ({} chars)", response == null ? 0 : response.length());
            return sensitiveDataGuard.sanitizeOutput(response);
        } catch (Exception e) {
            log.error("LLM call with tools failed", e);
            throw new LlmIntegrationException("Failed to get a response from the LLM", e);
        }
    }

    @Override
    public String generateWithTools(Prompt prompt, ToolCallbackProvider toolCallbackProvider) {
        assertWithinTokenBudget(prompt);
        log.debug("Sending prompt to LLM with a tool callback provider ({} messages)",
                prompt.getInstructions().size());
        try {
            String response = chatClient.prompt(prompt)
                    .toolCallbacks(toolCallbackProvider)
                    .call()
                    .content();
            log.debug("Received LLM response ({} chars)", response == null ? 0 : response.length());
            return sensitiveDataGuard.sanitizeOutput(response);
        } catch (Exception e) {
            log.error("LLM call with tool callback provider failed", e);
            throw new LlmIntegrationException("Failed to get a response from the LLM", e);
        }
    }

    /**
     * Estimates prompt size as total characters / 4 (a common rough
     * approximation for English text) and rejects before ever calling the
     * provider if it exceeds app.guardrails.max-prompt-tokens. Deliberately
     * outside the try/catch above: this is a guardrail rejection of an
     * oversized request, not a provider failure, so it must NOT be wrapped
     * as a 502 LlmIntegrationException - it maps to 413 Payload Too Large.
     */
    private void assertWithinTokenBudget(Prompt prompt) {
        int totalChars = prompt.getInstructions().stream()
                .mapToInt(message -> message.getText() == null ? 0 : message.getText().length())
                .sum();
        int estimatedTokens = totalChars / CHARS_PER_ESTIMATED_TOKEN;
        if (estimatedTokens > guardrailProperties.maxPromptTokens()) {
            aiPipelineMetrics.recordSafetyBoundTriggered("prompt_too_large");
            throw new PromptTooLargeException(
                    "Prompt is too large to send to the LLM: ~" + estimatedTokens + " estimated tokens exceeds "
                            + "the configured limit of " + guardrailProperties.maxPromptTokens()
                            + ". This guards against context-stuffing, e.g. an oversized document or an "
                            + "inflated conversation history.");
        }
    }
}
