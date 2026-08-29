package com.example.aiplatform.service;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.structured.SupportAnswerConverter;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.SupportAnswer;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import org.springframework.stereotype.Service;

@Service
public class ChatServiceImpl implements ChatService {

    private final LlmClientService llmClientService;
    private final PromptBuilder promptBuilder;
    private final SupportAnswerConverter supportAnswerConverter;
    private final PromptInjectionGuard promptInjectionGuard;

    public ChatServiceImpl(LlmClientService llmClientService,
                            PromptBuilder promptBuilder,
                            SupportAnswerConverter supportAnswerConverter,
                            PromptInjectionGuard promptInjectionGuard) {
        this.llmClientService = llmClientService;
        this.promptBuilder = promptBuilder;
        this.supportAnswerConverter = supportAnswerConverter;
        this.promptInjectionGuard = promptInjectionGuard;
    }

    @Override
    public ChatResponse answer(String message) {
        promptInjectionGuard.assertSafe(message);
        Prompt prompt = promptBuilder.buildSupportPrompt(message);
        String answer = llmClientService.generate(prompt);
        return new ChatResponse(answer, llmClientService.modelName());
    }

    /**
     * Note what still runs and what does not. The input guardrail runs first,
     * exactly as on the buffered path - a prompt-injection attempt is rejected
     * before a single token is generated, and rejecting early is if anything
     * more important here, because a stream cannot be taken back. The OUTPUT
     * guardrail does not run; see {@link ChatService#answerStreaming} and
     * {@code LlmClientService.generateStream} for why, and for what it would
     * take to change that.
     */
    @Override
    public Flux<String> answerStreaming(String message) {
        promptInjectionGuard.assertSafe(message);
        Prompt prompt = promptBuilder.buildSupportPrompt(message);
        return llmClientService.generateStream(prompt);
    }

    @Override
    public SupportAnswer answerStructured(String message) {
        promptInjectionGuard.assertSafe(message);
        Prompt prompt = promptBuilder.buildStructuredSupportPrompt(message, supportAnswerConverter.formatInstructions());
        String rawResponse = llmClientService.generate(prompt);
        return supportAnswerConverter.parse(rawResponse);
    }
}
