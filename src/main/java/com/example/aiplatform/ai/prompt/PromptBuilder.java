package com.example.aiplatform.ai.prompt;

import org.springframework.ai.chat.prompt.Prompt;

/**
 * Turns raw user input into a fully-formed {@link Prompt} (system persona +
 * rendered user template). Prompt-engineering concerns live here so the
 * service and LLM-integration layers never construct prompt text themselves.
 */
public interface PromptBuilder {

    Prompt buildSupportPrompt(String question);

    Prompt buildStructuredSupportPrompt(String question, String formatInstructions);

    Prompt buildRagPrompt(String question, String context);

    Prompt buildToolsSupportPrompt(String question);
}
