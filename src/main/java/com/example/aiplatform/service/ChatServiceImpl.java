package com.example.aiplatform.service;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.structured.SupportAnswerConverter;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAnswer;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {

    private final LlmClientService llmClientService;
    private final PromptBuilder promptBuilder;
    private final SupportAnswerConverter supportAnswerConverter;
    private final PromptInjectionGuard promptInjectionGuard;
    private final String model;

    public ChatServiceImpl(LlmClientService llmClientService,
                            PromptBuilder promptBuilder,
                            SupportAnswerConverter supportAnswerConverter,
                            PromptInjectionGuard promptInjectionGuard,
                            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.llmClientService = llmClientService;
        this.promptBuilder = promptBuilder;
        this.supportAnswerConverter = supportAnswerConverter;
        this.promptInjectionGuard = promptInjectionGuard;
        this.model = model;
    }

    @Override
    public ChatResponse answer(String message) {
        promptInjectionGuard.assertSafe(message);
        Prompt prompt = promptBuilder.buildSupportPrompt(message);
        String answer = llmClientService.generate(prompt);
        return new ChatResponse(answer, model);
    }

    @Override
    public SupportAnswer answerStructured(String message) {
        promptInjectionGuard.assertSafe(message);
        Prompt prompt = promptBuilder.buildStructuredSupportPrompt(message, supportAnswerConverter.formatInstructions());
        String rawResponse = llmClientService.generate(prompt);
        return supportAnswerConverter.parse(rawResponse);
    }
}
