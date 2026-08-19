package com.example.aiplatform.service;

import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.tools.CallerContextHolder;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The Phase 8 flow: User -> LLM -> tool selection -> Java tool -> tool
 * result -> LLM -> final response. This class only sets up the caller
 * context and hands the tool-aware prompt to the LLM client - the actual
 * "decide whether to call a tool, call it, feed the result back, produce a
 * final answer" loop happens inside Spring AI's ChatClient, not here.
 */
@Service
public class SupportAssistantServiceImpl implements SupportAssistantService {

    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final SupportTools supportTools;
    private final String model;

    public SupportAssistantServiceImpl(PromptBuilder promptBuilder,
                                        LlmClientService llmClientService,
                                        SupportTools supportTools,
                                        @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.supportTools = supportTools;
        this.model = model;
    }

    @Override
    public ChatResponse assist(String customerId, String message) {
        // Set BEFORE the LLM call and cleared in a finally block: the caller
        // identity must be established by trusted request-handling code, not
        // by anything the model decides mid-conversation, and must never
        // leak into a pooled thread's next unrelated request.
        CallerContextHolder.setCurrentCustomerId(customerId);
        try {
            Prompt prompt = promptBuilder.buildToolsSupportPrompt(message);
            String answer = llmClientService.generateWithTools(prompt, supportTools);
            return new ChatResponse(answer, model);
        } finally {
            CallerContextHolder.clear();
        }
    }
}
