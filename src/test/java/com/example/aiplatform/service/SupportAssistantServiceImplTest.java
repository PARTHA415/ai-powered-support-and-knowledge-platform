package com.example.aiplatform.service;

import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.tools.CallerContextHolder;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportAssistantServiceImplTest {

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private LlmClientService llmClientService;

    @Mock
    private SupportTools supportTools;

    @Test
    void assistSetsCallerContextBeforeCallingLlmAndClearsItAfter() {
        SupportAssistantServiceImpl service =
                new SupportAssistantServiceImpl(promptBuilder, llmClientService, supportTools, "gpt-4o-mini");
        Prompt prompt = new Prompt(new UserMessage("What's the status of order ORD-1001?"));
        when(promptBuilder.buildToolsSupportPrompt("What's the status of order ORD-1001?")).thenReturn(prompt);
        when(llmClientService.generateWithTools(prompt, supportTools)).thenAnswer(invocation -> {
            // Assert from inside the mocked LLM call - this is the moment the
            // caller context must already be set, since a real tool call
            // would happen during this call.
            assertThat(CallerContextHolder.getCurrentCustomerId()).isEqualTo("CUST-1001");
            return "Order ORD-1001 is currently SHIPPED.";
        });

        ChatResponse response = service.assist("CUST-1001", "What's the status of order ORD-1001?");

        assertThat(response.answer()).isEqualTo("Order ORD-1001 is currently SHIPPED.");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        verify(llmClientService).generateWithTools(prompt, supportTools);
        assertThatCallerContextIsCleared();
    }

    @Test
    void callerContextIsClearedEvenWhenLlmCallFails() {
        SupportAssistantServiceImpl service =
                new SupportAssistantServiceImpl(promptBuilder, llmClientService, supportTools, "gpt-4o-mini");
        Prompt prompt = new Prompt(new UserMessage("question"));
        when(promptBuilder.buildToolsSupportPrompt(eq("question"))).thenReturn(prompt);
        when(llmClientService.generateWithTools(prompt, supportTools))
                .thenThrow(new RuntimeException("LLM unavailable"));

        try {
            service.assist("CUST-1001", "question");
        } catch (RuntimeException expected) {
            // expected - only checking that context cleanup still happened
        }

        assertThatCallerContextIsCleared();
    }

    private static void assertThatCallerContextIsCleared() {
        assertThat(catchThrowable(CallerContextHolder::getCurrentCustomerId))
                .isInstanceOf(IllegalStateException.class);
    }
}
