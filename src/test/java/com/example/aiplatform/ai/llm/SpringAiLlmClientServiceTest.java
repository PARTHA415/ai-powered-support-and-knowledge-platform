package com.example.aiplatform.ai.llm;

import com.example.aiplatform.ai.guardrails.SensitiveDataGuard;
import com.example.aiplatform.ai.guardrails.TokenBudgetGuard;
import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.config.ModelPricingProperties;
import com.example.aiplatform.config.ModelTierProperties;
import com.example.aiplatform.exception.PromptTooLargeException;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.observability.CostMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
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
 * in the application flows through, so it's where the token-budget ceiling,
 * output sensitive-data scrubbing, cost metering and the per-caller token
 * budget are enforced once, for every caller, rather than being duplicated in
 * each business service.
 */
@ExtendWith(MockitoExtension.class)
class SpringAiLlmClientServiceTest {

    private static final ModelTierProperties MODEL_TIERS = new ModelTierProperties("gpt-4o-mini", "gpt-4o");

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
    @Mock
    private TokenBudgetGuard tokenBudgetGuard;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiPipelineMetrics aiPipelineMetrics = new AiPipelineMetrics(meterRegistry);

    private SpringAiLlmClientService newService(GuardrailProperties guardrailProperties) {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        return newService(guardrailProperties, aiPipelineMetrics, pricedCostMeter());
    }

    private SpringAiLlmClientService newService(GuardrailProperties guardrailProperties,
                                                 AiPipelineMetrics metrics, CostMeter costMeter) {
        return new SpringAiLlmClientService(chatClientBuilder, guardrailProperties, sensitiveDataGuard,
                metrics, MODEL_TIERS, costMeter, tokenBudgetGuard);
    }

    private CostMeter pricedCostMeter() {
        return new CostMeter(meterRegistry, new ModelPricingProperties(java.util.Map.of(
                "gpt-4o", new ModelPricingProperties.ModelPrice(2.5, 10.0, 1.25),
                "gpt-4o-mini", new ModelPricingProperties.ModelPrice(0.15, 0.6, 0.075))));
    }

    private static ChatResponse chatResponse(String text, String model, int promptTokens, int completionTokens) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build());
    }

    @Test
    void generateSendsThePromptAndReturnsTheSanitizedResponse() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(chatResponse("raw response with a secret", "gpt-4o", 100, 20));
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
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("raw tool answer", "gpt-4o", 50, 10));
        when(sensitiveDataGuard.sanitizeOutput("raw tool answer")).thenReturn("sanitized tool answer");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        String result = service.generateWithTools(new Prompt(new UserMessage("What's my order status?")), tool);

        assertThat(result).isEqualTo("sanitized tool answer");
    }

    @Test
    void modelNameReportsTheConfiguredModelForEachTier() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000), aiPipelineMetrics,
                pricedCostMeter());

        assertThat(service.modelName(ModelTier.FAST)).isEqualTo("gpt-4o-mini");
        assertThat(service.modelName(ModelTier.CAPABLE)).isEqualTo("gpt-4o");
        assertThat(service.modelName()).isEqualTo("gpt-4o");
    }

    // --- cost metering ---

    /**
     * The point of tiering is that the same token count costs different
     * amounts, so cost has to be attributed to the tier that incurred it. A
     * prompt naming the fast model must be metered as FAST even though nothing
     * told the client which tier it was - the tier is read back from the model
     * the prompt asked for.
     */
    @Test
    void tokensAndCostAreRecordedAgainstTheTierThePromptAskedFor() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse())
                .thenReturn(chatResponse("planned", "gpt-4o-mini", 1_000_000, 1_000_000));
        when(sensitiveDataGuard.sanitizeOutput("planned")).thenReturn("planned");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        Prompt fastPrompt = new Prompt(List.of(new UserMessage("route this")),
                ChatOptions.builder().model("gpt-4o-mini").build());

        service.generate(fastPrompt);

        assertThat(meterRegistry.get("ai.tokens").tag("tier", "fast").tag("type", "prompt").counter().count())
                .isEqualTo(1_000_000.0);
        // 1M prompt at 0.15 + 1M completion at 0.60
        assertThat(meterRegistry.get("ai.cost.usd").tag("tier", "fast").counter().count())
                .isEqualTo(0.75);
    }

    @Test
    void aCompletedCallIsChargedToTheCallersTokenBudget() {
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("answer", "gpt-4o", 120, 30));
        when(sensitiveDataGuard.sanitizeOutput("answer")).thenReturn("answer");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        service.generate(new Prompt(new UserMessage("Hi")));

        verify(tokenBudgetGuard).assertWithinBudget();
        verify(tokenBudgetGuard).record(
                org.mockito.ArgumentMatchers.eq(new LlmCallUsage("gpt-4o", ModelTier.CAPABLE, 120, 0, 30)),
                org.mockito.ArgumentMatchers.any());
    }

    // --- maximum token/context limit guardrail ---

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

    /**
     * The size guardrail runs BEFORE the per-caller budget check, so an
     * oversized prompt costs the caller nothing - it is refused without ever
     * consulting, or charging against, their allowance.
     */
    @Test
    void anOversizedPromptIsRefusedBeforeTheCallersBudgetIsEvenConsulted() {
        Prompt oversized = new Prompt(new UserMessage("This message is deliberately long enough to blow the budget."));
        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 5));

        assertThatThrownBy(() -> service.generate(oversized)).isInstanceOf(PromptTooLargeException.class);

        verifyNoInteractions(tokenBudgetGuard);
    }

    @Test
    void exceedingTheTokenBudgetRecordsASafetyBoundTriggeredMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiPipelineMetrics metrics = new AiPipelineMetrics(registry);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 5), metrics,
                new CostMeter(registry, new ModelPricingProperties(java.util.Map.of())));
        Prompt oversized = new Prompt(new UserMessage("This message is deliberately long enough to blow the budget."));

        assertThatThrownBy(() -> service.generate(oversized)).isInstanceOf(PromptTooLargeException.class);

        assertThat(registry.get("ai.safety.bound.triggered").tag("reason", "prompt_too_large").counter().count())
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
        when(callResponseSpec.chatResponse()).thenReturn(chatResponse("short answer", "gpt-4o", 5, 2));
        when(sensitiveDataGuard.sanitizeOutput("short answer")).thenReturn("short answer");

        SpringAiLlmClientService service = newService(new GuardrailProperties(20, 6000));
        String result = service.generate(new Prompt(new UserMessage("Hi")));

        assertThat(result).isEqualTo("short answer");
    }
}
