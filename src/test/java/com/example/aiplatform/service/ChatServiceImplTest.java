package com.example.aiplatform.service;

import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.structured.SupportAnswerConverter;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.model.ConfidenceLevel;
import com.example.aiplatform.model.SupportAnswer;
import com.example.aiplatform.model.SupportCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private LlmClientService llmClientService;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private SupportAnswerConverter supportAnswerConverter;

    @Test
    void answerBuildsPromptAndDelegatesToLlmClient() {
        ChatServiceImpl chatService = new ChatServiceImpl(
                llmClientService, promptBuilder, supportAnswerConverter, "gpt-4o-mini");
        Prompt prompt = new Prompt(new UserMessage("How do I reset my password?"));
        when(promptBuilder.buildSupportPrompt("How do I reset my password?")).thenReturn(prompt);
        when(llmClientService.generate(prompt)).thenReturn("Go to Settings > Security > Reset Password.");

        ChatResponse response = chatService.answer("How do I reset my password?");

        assertThat(response.answer()).isEqualTo("Go to Settings > Security > Reset Password.");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        verify(llmClientService).generate(prompt);
    }

    @Test
    void answerStructuredBuildsFormatAwarePromptAndParsesResult() {
        ChatServiceImpl chatService = new ChatServiceImpl(
                llmClientService, promptBuilder, supportAnswerConverter, "gpt-4o-mini");
        Prompt prompt = new Prompt(new UserMessage("How do I reset my password?"));
        SupportAnswer expected = new SupportAnswer(
                "Go to Settings > Security > Reset Password.", SupportCategory.ACCOUNT, ConfidenceLevel.HIGH, false);

        when(supportAnswerConverter.formatInstructions()).thenReturn("Respond with JSON: {...}");
        when(promptBuilder.buildStructuredSupportPrompt("How do I reset my password?", "Respond with JSON: {...}"))
                .thenReturn(prompt);
        when(llmClientService.generate(prompt)).thenReturn("{\"raw\":\"json\"}");
        when(supportAnswerConverter.parse("{\"raw\":\"json\"}")).thenReturn(expected);

        SupportAnswer actual = chatService.answerStructured("How do I reset my password?");

        assertThat(actual).isEqualTo(expected);
    }
}
