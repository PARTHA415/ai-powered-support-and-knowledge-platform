package com.example.aiplatform.ai.llm;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * Thin seam over whichever LLM provider is wired up. Keeps callers (the
 * business service layer) from depending on a specific provider's SDK or
 * Spring AI's fluent API directly, per the "no LLM provider lock-in" rule.
 *
 * Takes a fully-formed {@link Prompt} rather than a raw string so that all
 * prompt-engineering concerns (system persona, templating, few-shot
 * examples) stay in the ai/prompt layer instead of leaking in here.
 */
public interface LlmClientService {

    String generate(Prompt prompt);

    /**
     * Same as {@link #generate(Prompt)}, but makes the given tool objects'
     * {@code @Tool}-annotated methods available for the model to call. The
     * full tool-calling loop (model requests a call, the method runs, the
     * result goes back to the model, the model produces a final answer) is
     * handled internally by the provider integration - this call still
     * returns only the final text answer.
     */
    String generateWithTools(Prompt prompt, Object... tools);

    /**
     * Same as {@link #generateWithTools(Prompt, Object...)}, but for tools
     * sourced from a {@link ToolCallbackProvider} rather than annotated Java
     * objects - the seam Phase 13's MCP client demonstration uses, since an
     * MCP-discovered tool is already a {@code ToolCallback}, not a POJO with
     * {@code @Tool} methods to reflect over.
     */
    String generateWithTools(Prompt prompt, ToolCallbackProvider toolCallbackProvider);
}
