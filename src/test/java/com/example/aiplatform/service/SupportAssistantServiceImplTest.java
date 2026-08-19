package com.example.aiplatform.service;

import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.model.ChatResponse;
import com.example.aiplatform.security.CurrentUser;
import com.example.aiplatform.security.TestPrincipals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As of Phase 11, this class does nothing to set up caller identity itself -
 * Spring Security has already authenticated the request before assist() is
 * called, so these tests only need to fake that prior step via
 * {@link TestPrincipals}, the same way a real HTTP Basic login would leave
 * the SecurityContext populated for the rest of the request.
 */
@ExtendWith(MockitoExtension.class)
class SupportAssistantServiceImplTest {

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private LlmClientService llmClientService;

    @Mock
    private SupportTools supportTools;

    @BeforeEach
    void authenticateAsCustomer() {
        TestPrincipals.authenticateAs(TestPrincipals.customer("CUST-1001"));
    }

    @AfterEach
    void clearSecurityContext() {
        TestPrincipals.clear();
    }

    @Test
    void assistDelegatesToLlmClientWithTheAuthenticatedCallerAlreadyReadable() {
        SupportAssistantServiceImpl service =
                new SupportAssistantServiceImpl(promptBuilder, llmClientService, supportTools, "gpt-4o-mini");
        Prompt prompt = new Prompt(new UserMessage("What's the status of order ORD-1001?"));
        when(promptBuilder.buildToolsSupportPrompt("What's the status of order ORD-1001?")).thenReturn(prompt);
        when(llmClientService.generateWithTools(prompt, supportTools)).thenAnswer(invocation -> {
            // A real tool call happening during this LLM call relies on the
            // caller already being authenticated - proven here by reading it
            // back, not by this service class setting it up (it no longer does).
            assertThat(CurrentUser.customerId()).isEqualTo("CUST-1001");
            return "Order ORD-1001 is currently SHIPPED.";
        });

        ChatResponse response = service.assist("What's the status of order ORD-1001?");

        assertThat(response.answer()).isEqualTo("Order ORD-1001 is currently SHIPPED.");
        assertThat(response.model()).isEqualTo("gpt-4o-mini");
        verify(llmClientService).generateWithTools(prompt, supportTools);
    }

    @Test
    void llmCallFailurePropagates() {
        SupportAssistantServiceImpl service =
                new SupportAssistantServiceImpl(promptBuilder, llmClientService, supportTools, "gpt-4o-mini");
        Prompt prompt = new Prompt(new UserMessage("question"));
        when(promptBuilder.buildToolsSupportPrompt(eq("question"))).thenReturn(prompt);
        when(llmClientService.generateWithTools(prompt, supportTools))
                .thenThrow(new RuntimeException("LLM unavailable"));

        assertThatThrownBy(() -> service.assist("question")).isInstanceOf(RuntimeException.class);
    }
}
