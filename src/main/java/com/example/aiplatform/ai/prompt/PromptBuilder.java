package com.example.aiplatform.ai.prompt;

import org.springframework.ai.chat.prompt.Prompt;

/**
 * Turns raw user input into a fully-formed {@link Prompt} (system persona +
 * rendered user template) and decides which model tier and sampling
 * temperature it carries. Prompt-engineering concerns live here so the
 * service and LLM-integration layers never construct prompt text themselves.
 */
public interface PromptBuilder {

    Prompt buildSupportPrompt(String question);

    Prompt buildStructuredSupportPrompt(String question, String formatInstructions);

    Prompt buildRagPrompt(String question, String context);

    Prompt buildToolsSupportPrompt(String question);

    Prompt buildAgentPlanningPrompt(String question, String formatInstructions);

    Prompt buildAgentFinalPrompt(String question, String evidence);

    /**
     * The evaluation-only prompt that asks a model to score another model's
     * answer for groundedness and relevance.
     *
     * <p>It lives on the same interface as the serving prompts on purpose. A
     * judge prompt is a prompt: it needs the same untrusted-content fencing as
     * the RAG prompt (it is fed retrieved context and a generated answer, both
     * of which can carry injected instructions), the same deterministic
     * sampling as the structured-output prompts, and the same single place to
     * be reviewed. Building it inside the evaluation package would have
     * duplicated all three and put prompt text outside the prompt layer.
     */
    Prompt buildJudgePrompt(String question, String answer, String context, String formatInstructions);
}
