package com.example.aiplatform.service;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The Phase 8 flow: User -> LLM -> tool selection -> Java tool -> tool
 * result -> LLM -> final response. As of Phase 11, no caller-context setup
 * happens here at all - Spring Security has already authenticated the
 * caller before this method runs, and SupportTools reads that identity
 * directly via {@link com.example.aiplatform.security.CurrentUser} whenever
 * it needs to authorize a lookup. This class's only job is building the
 * tool-aware prompt and handing it to the LLM client; the actual "decide
 * whether to call a tool, call it, feed the result back, produce a final
 * answer" loop happens inside Spring AI's ChatClient, not here.
 */
@Service
public class SupportAssistantServiceImpl implements SupportAssistantService {

    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final SupportTools supportTools;
    private final PromptInjectionGuard promptInjectionGuard;
    private final String model;

    public SupportAssistantServiceImpl(PromptBuilder promptBuilder,
                                        LlmClientService llmClientService,
                                        SupportTools supportTools,
                                        PromptInjectionGuard promptInjectionGuard,
                                        @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.supportTools = supportTools;
        this.promptInjectionGuard = promptInjectionGuard;
        this.model = model;
    }

    @Override
    public ChatResponse assist(String message) {
        promptInjectionGuard.assertSafe(message);
        Prompt prompt = promptBuilder.buildToolsSupportPrompt(message);
        String answer = llmClientService.generateWithTools(prompt, supportTools);
        return new ChatResponse(answer, model);
    }
}
