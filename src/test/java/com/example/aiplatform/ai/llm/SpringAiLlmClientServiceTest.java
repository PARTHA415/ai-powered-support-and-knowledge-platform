package com.example.aiplatform.ai.llm;

import com.example.aiplatform.ai.guardrails.SensitiveDataGuard;
import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.exception.PromptTooLargeException;
import com.example.aiplatform.observability.AiPipelineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SpringAiLlmClientService} is the single choke point every LLM call
 * in the application flows through, so it's where the Phase 12 token-budget
 * ceiling and output sensitive-data scrubbing are enforced once, for every
 * caller, rather than being duplicated in each business service.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiLlmClientServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;
    @Mock
    private SensitiveDataGuard sensitiveDataGuard;

    private final AiPipelineMetrics aiPipelineMetrics = new AiPipelineMetrics(new SimpleMeterRegistry());

    private SpringAiLlmClientService newService(GuardrailProperties guardrailProperties) {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        return new SpringAiLlmClientService(chatClientBuilder, guardrailProperties, sensitiveDataGuard, aiPipelineMetrics);
    }

    @Test
    void generateSendsThePromptAndReturnsTheSanitizedResponse() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("raw response with a secret");
        when(sensitiveDataGuard.sanitizeOutput("raw response with a secret")).thenReturn("raw response with [REDACTED]");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        String result = service.generate(new Prompt(new UserMessage("How do I reset my password?")));

        assertThat(result).isEqualTo("raw response with [REDACTED]");
        verify(sensitiveDataGuard).sanitizeOutput("raw response with a secret");
    }

    @Test
    void generateWithToolsPassesToolsThroughAndReturnsTheSanitizedResponse() {
        Object tool = new Object();
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.tools(tool)).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("raw tool answer");
        when(sensitiveDataGuard.sanitizeOutput("raw tool answer")).thenReturn("sanitized tool answer");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        String result = service.generateWithTools(new Prompt(new UserMessage("What's my order status?")), tool);

        assertThat(result).isEqualTo("sanitized tool answer");
    }

    // --- Phase 12: maximum token/context limit guardrail ---

    @Test
    void generateRejectsAPromptThatExceedsTheConfiguredTokenBudgetWithoutCallingTheProvider() {
        // ~40 chars -> ~10 estimated tokens (chars/4), well past a budget of 5.
        Prompt oversized = new Prompt(List.of(
                new SystemMessage("system"),
                new UserMessage("This message is deliberately long enough to blow the budget.")));

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 5));

        assertThatThrownBy(() -> service.generate(oversized))
                .isInstanceOf(PromptTooLargeException.class);
        verifyNoInteractions(sensitiveDataGuard);
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    void exceedingTheTokenBudgetRecordsASafetyBoundTriggeredMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AiPipelineMetrics metrics = new AiPipelineMetrics(meterRegistry);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        SpringAiLlmClientService service =
                new SpringAiLlmClientService(chatClientBuilder, new GuardrailProperties(20, 5), sensitiveDataGuard, metrics);
        Prompt oversized = new Prompt(new UserMessage("This message is deliberately long enough to blow the budget."));

        assertThatThrownBy(() -> service.generate(oversized)).isInstanceOf(PromptTooLargeException.class);

        assertThat(meterRegistry.get("ai.safety.bound.triggered").tag("reason", "prompt_too_large").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void generateWithToolsAlsoEnforcesTheTokenBudget() {
        Prompt oversized = new Prompt(new UserMessage("This message is deliberately long enough to blow the budget."));
        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 5));

        assertThatThrownBy(() -> service.generateWithTools(oversized, new Object()))
                .isInstanceOf(PromptTooLargeException.class);
        verify(chatClient, never()).prompt(any(Prompt.class));
    }

    @Test
    void aPromptWellWithinTheBudgetIsSentNormally() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("short answer");
        when(sensitiveDataGuard.sanitizeOutput("short answer")).thenReturn("short answer");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        String result = service.generate(new Prompt(new UserMessage("Hi")));

        assertThat(result).isEqualTo("short answer");
    }
}
