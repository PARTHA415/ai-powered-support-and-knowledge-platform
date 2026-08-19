package com.example.aiplatform.service;

import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.ai.structured.AgentPlanConverter;
import com.example.aiplatform.ai.tools.CallerContextHolder;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.config.AgentProperties;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.model.AgentPlan;
import com.example.aiplatform.model.AgentResponse;
import com.example.aiplatform.model.AgentStepRecord;
import com.example.aiplatform.model.SemanticSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the distinct execution paths through the agent workflow: which
 * capabilities get planned and run, how the workflow degrades when a step
 * fails, and how the two safety bounds (max iterations, timeout) truncate
 * execution rather than letting it run away.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    private static final RagProperties RAG_PROPERTIES = new RagProperties(800, 100, 5, 0.5);
    private static final AgentProperties DEFAULT_AGENT_PROPERTIES = new AgentProperties(5, 30);
    private static final String QUESTION = "What's the status of order ORD-1001?";
    private static final String CUSTOMER_ID = "CUST-1001";

    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LlmClientService llmClientService;
    @Mock
    private AgentPlanConverter agentPlanConverter;
    @Mock
    private SemanticSearchService semanticSearchService;
    @Mock
    private SupportTools supportTools;

    @AfterEach
    void clearCallerContext() {
        CallerContextHolder.clear();
    }

    private AgentServiceImpl newService(AgentProperties agentProperties) {
        return new AgentServiceImpl(promptBuilder, llmClientService, agentPlanConverter, semanticSearchService,
                supportTools, RAG_PROPERTIES, agentProperties, "gpt-4o-mini");
    }

    private Prompt stubPlanning(AgentPlan plan) {
        Prompt planningPrompt = new Prompt(new UserMessage("planning"));
        when(agentPlanConverter.formatInstructions()).thenReturn("format instructions");
        when(promptBuilder.buildAgentPlanningPrompt(eq(QUESTION), eq("format instructions"))).thenReturn(planningPrompt);
        when(llmClientService.generate(planningPrompt)).thenReturn("raw-plan-json");
        when(agentPlanConverter.parse("raw-plan-json")).thenReturn(plan);
        return planningPrompt;
    }

    private Prompt stubFinalize(String answer) {
        Prompt finalPrompt = new Prompt(new UserMessage("final"));
        when(promptBuilder.buildAgentFinalPrompt(eq(QUESTION), anyString())).thenReturn(finalPrompt);
        when(llmClientService.generate(finalPrompt)).thenReturn(answer);
        return finalPrompt;
    }

    // --- path: knowledge base only ---

    @Test
    void knowledgeBaseOnlyPathRunsKbStepAndSkipsToolStep() {
        stubPlanning(new AgentPlan("needs docs", true, false));
        when(semanticSearchService.search(QUESTION, RAG_PROPERTIES.topK()))
                .thenReturn(List.of(new SemanticSearchResult("Password Reset Guide", "Go to Settings.", 0.9)));
        stubFinalize("Here's how to reset your password.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.answer()).isEqualTo("Here's how to reset your password.");
        assertThat(response.auditTrail().knowledgeBasePlanned()).isTrue();
        assertThat(response.auditTrail().businessToolPlanned()).isFalse();
        assertThat(capabilities(response)).containsExactly("PLANNING", "KNOWLEDGE_BASE", "FINALIZE");
        verify(llmClientService, never()).generateWithTools(any(), any());
    }

    // --- path: business tool only, and tool-authorization context wiring ---

    @Test
    void businessToolOnlyPathRunsToolStepWithCorrectCallerContextAndSkipsKb() {
        stubPlanning(new AgentPlan("needs order lookup", false, true));
        Prompt toolsPrompt = new Prompt(new UserMessage("tools"));
        when(promptBuilder.buildToolsSupportPrompt(QUESTION)).thenReturn(toolsPrompt);
        when(llmClientService.generateWithTools(toolsPrompt, supportTools)).thenAnswer(invocation -> {
            // The caller identity must already be set by the time a tool
            // round can happen - this is what SupportTools' authorization
            // checks (Phase 8) rely on.
            assertThat(CallerContextHolder.getCurrentCustomerId()).isEqualTo(CUSTOMER_ID);
            return "Order ORD-1001 is SHIPPED.";
        });
        stubFinalize("Your order ORD-1001 is SHIPPED.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.auditTrail().knowledgeBasePlanned()).isFalse();
        assertThat(response.auditTrail().businessToolPlanned()).isTrue();
        assertThat(capabilities(response)).containsExactly("PLANNING", "BUSINESS_TOOL", "FINALIZE");
        verify(semanticSearchService, never()).search(any(), any(Integer.class));
        // Cleared after the request completes, so it can't leak into another request on a pooled thread.
        assertThat(catchThrowable(CallerContextHolder::getCurrentCustomerId)).isInstanceOf(IllegalStateException.class);
    }

    // --- path: both capabilities, results combined ---

    @Test
    void bothCapabilitiesPathCombinesEvidenceFromBoth() {
        stubPlanning(new AgentPlan("needs both", true, true));
        when(semanticSearchService.search(QUESTION, RAG_PROPERTIES.topK()))
                .thenReturn(List.of(new SemanticSearchResult("Shipping Policy", "Orders ship in 2 days.", 0.9)));
        Prompt toolsPrompt = new Prompt(new UserMessage("tools"));
        when(promptBuilder.buildToolsSupportPrompt(QUESTION)).thenReturn(toolsPrompt);
        when(llmClientService.generateWithTools(toolsPrompt, supportTools)).thenReturn("Order ORD-1001 is SHIPPED.");
        Prompt finalPrompt = new Prompt(new UserMessage("final"));
        when(promptBuilder.buildAgentFinalPrompt(eq(QUESTION), anyString())).thenReturn(finalPrompt);
        when(llmClientService.generate(finalPrompt)).thenReturn("Combined answer.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        service.handle(CUSTOMER_ID, QUESTION);

        ArgumentCaptor<String> evidenceCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptBuilder).buildAgentFinalPrompt(eq(QUESTION), evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue())
                .contains("Shipping Policy")
                .contains("Order ORD-1001 is SHIPPED.");
    }

    // --- path: neither capability needed ---

    @Test
    void neitherCapabilityPathSkipsBothAndFinalizesWithNoEvidencePlaceholder() {
        stubPlanning(new AgentPlan("simple greeting, no lookup needed", false, false));
        Prompt finalPrompt = new Prompt(new UserMessage("final"));
        when(promptBuilder.buildAgentFinalPrompt(eq(QUESTION), anyString())).thenReturn(finalPrompt);
        when(llmClientService.generate(finalPrompt)).thenReturn("Hello! How can I help?");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(capabilities(response)).containsExactly("PLANNING", "FINALIZE");
        ArgumentCaptor<String> evidenceCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptBuilder).buildAgentFinalPrompt(eq(QUESTION), evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue()).isEqualTo("No additional evidence was gathered for this question.");
    }

    // --- failure handling: planning fails ---

    @Test
    void planningFailureDegradesToNoCapabilitiesButStillProducesAnAnswer() {
        Prompt planningPrompt = new Prompt(new UserMessage("planning"));
        when(agentPlanConverter.formatInstructions()).thenReturn("format instructions");
        when(promptBuilder.buildAgentPlanningPrompt(eq(QUESTION), eq("format instructions"))).thenReturn(planningPrompt);
        when(llmClientService.generate(planningPrompt))
                .thenThrow(new LlmIntegrationException("LLM unavailable", new RuntimeException()));
        stubFinalize("I can help, but couldn't determine what to look up.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.auditTrail().knowledgeBasePlanned()).isFalse();
        assertThat(response.auditTrail().businessToolPlanned()).isFalse();
        AgentStepRecord planningStep = response.auditTrail().steps().get(0);
        assertThat(planningStep.capability()).isEqualTo("PLANNING");
        assertThat(planningStep.success()).isFalse();
        verify(semanticSearchService, never()).search(any(), any(Integer.class));
        verify(llmClientService, never()).generateWithTools(any(), any());
    }

    // --- failure handling: knowledge base step fails ---

    @Test
    void knowledgeBaseFailureDegradesGracefullyAndStillFinalizes() {
        stubPlanning(new AgentPlan("needs docs", true, false));
        when(semanticSearchService.search(QUESTION, RAG_PROPERTIES.topK()))
                .thenThrow(new RuntimeException("pgvector unavailable"));
        stubFinalize("I couldn't search the knowledge base right now.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.answer()).isEqualTo("I couldn't search the knowledge base right now.");
        AgentStepRecord kbStep = findStep(response, "KNOWLEDGE_BASE");
        assertThat(kbStep.success()).isFalse();
        ArgumentCaptor<String> evidenceCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptBuilder).buildAgentFinalPrompt(eq(QUESTION), evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue()).contains("search failed and was skipped");
    }

    // --- failure handling: business tool step fails ---

    @Test
    void businessToolFailureDegradesGracefullyAndStillFinalizes() {
        stubPlanning(new AgentPlan("needs order lookup", false, true));
        Prompt toolsPrompt = new Prompt(new UserMessage("tools"));
        when(promptBuilder.buildToolsSupportPrompt(QUESTION)).thenReturn(toolsPrompt);
        when(llmClientService.generateWithTools(toolsPrompt, supportTools))
                .thenThrow(new LlmIntegrationException("LLM unavailable", new RuntimeException()));
        stubFinalize("I couldn't look up that order right now.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.answer()).isEqualTo("I couldn't look up that order right now.");
        AgentStepRecord toolStep = findStep(response, "BUSINESS_TOOL");
        assertThat(toolStep.success()).isFalse();
    }

    // --- safety bound: max iterations ---

    @Test
    void maxIterationsCapStopsExecutionBeforeSecondCapability() {
        stubPlanning(new AgentPlan("needs both", true, true));
        when(semanticSearchService.search(QUESTION, RAG_PROPERTIES.topK()))
                .thenReturn(List.of(new SemanticSearchResult("Doc", "content", 0.9)));
        stubFinalize("Partial answer.");

        AgentServiceImpl service = newService(new AgentProperties(1, 30));
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.auditTrail().maxIterationsExceeded()).isTrue();
        assertThat(capabilities(response)).containsExactly("PLANNING", "KNOWLEDGE_BASE", "FINALIZE");
        verify(llmClientService, never()).generateWithTools(any(), any());
    }

    // --- safety bound: timeout ---

    @Test
    void timeoutStopsAllCapabilityExecution() {
        stubPlanning(new AgentPlan("needs both", true, true));
        stubFinalize("Answered without gathering evidence due to timeout.");

        // Any nonzero elapsed wall-clock time exceeds a zero-second budget,
        // so the very first capability-loop check trips the timeout - a
        // deterministic way to exercise this path without sleeps.
        AgentServiceImpl service = newService(new AgentProperties(5, 0.0));
        AgentResponse response = service.handle(CUSTOMER_ID, QUESTION);

        assertThat(response.auditTrail().timedOut()).isTrue();
        assertThat(capabilities(response)).containsExactly("PLANNING", "FINALIZE");
        verify(semanticSearchService, never()).search(any(), any(Integer.class));
        verify(llmClientService, never()).generateWithTools(any(), any());
    }

    // --- failure handling: finalize itself fails, and this is NOT swallowed ---

    @Test
    void finalizeFailurePropagatesRatherThanBeingSwallowed() {
        stubPlanning(new AgentPlan("simple", false, false));
        Prompt finalPrompt = new Prompt(new UserMessage("final"));
        when(promptBuilder.buildAgentFinalPrompt(eq(QUESTION), anyString())).thenReturn(finalPrompt);
        when(llmClientService.generate(finalPrompt))
                .thenThrow(new LlmIntegrationException("LLM unavailable", new RuntimeException()));

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);

        assertThatThrownBy(() -> service.handle(CUSTOMER_ID, QUESTION))
                .isInstanceOf(LlmIntegrationException.class);
    }

    private static List<String> capabilities(AgentResponse response) {
        return response.auditTrail().steps().stream().map(AgentStepRecord::capability).toList();
    }

    private static AgentStepRecord findStep(AgentResponse response, String capability) {
        Set<String> found = response.auditTrail().steps().stream().map(AgentStepRecord::capability)
                .collect(Collectors.toSet());
        return response.auditTrail().steps().stream()
                .filter(step -> step.capability().equals(capability))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No step for capability " + capability + ", found: " + found));
    }
}
