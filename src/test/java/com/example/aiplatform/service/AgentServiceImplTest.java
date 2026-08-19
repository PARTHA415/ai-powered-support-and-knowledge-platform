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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the distinct execution paths through the agent workflow: which
 * capabilities get planned and run, how the workflow degrades when a step
 * fails, how the two safety bounds (max iterations, timeout) truncate
 * execution, and - Phase 10 - how conversation history is threaded into
 * every LLM call and persisted after each turn.
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceImplTest {

    private static final RagProperties RAG_PROPERTIES = new RagProperties(800, 100, 5, 0.5);
    private static final AgentProperties DEFAULT_AGENT_PROPERTIES = new AgentProperties(5, 30);
    private static final String QUESTION = "What's the status of order ORD-1001?";
    private static final String CUSTOMER_ID = "CUST-1001";
    private static final String CONVERSATION_ID = "conv-1";

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
    @Mock
    private ChatMemory chatMemory;

    @AfterEach
    void clearCallerContext() {
        CallerContextHolder.clear();
    }

    private AgentServiceImpl newService(AgentProperties agentProperties) {
        return new AgentServiceImpl(promptBuilder, llmClientService, agentPlanConverter, semanticSearchService,
                supportTools, chatMemory, RAG_PROPERTIES, agentProperties, "gpt-4o-mini");
    }

    // chatMemory.get(...) is left unstubbed in most tests below - Mockito's
    // default answer for a List-returning method is an empty list, which is
    // exactly "no prior history" and needs no explicit stubbing.

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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.answer()).isEqualTo("Here's how to reset your password.");
        assertThat(response.auditTrail().knowledgeBasePlanned()).isTrue();
        assertThat(response.auditTrail().businessToolPlanned()).isFalse();
        assertThat(capabilities(response))
                .containsExactly("MEMORY_RETRIEVAL", "PLANNING", "KNOWLEDGE_BASE", "FINALIZE", "MEMORY_SAVE");
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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.auditTrail().knowledgeBasePlanned()).isFalse();
        assertThat(response.auditTrail().businessToolPlanned()).isTrue();
        assertThat(capabilities(response))
                .containsExactly("MEMORY_RETRIEVAL", "PLANNING", "BUSINESS_TOOL", "FINALIZE", "MEMORY_SAVE");
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
        service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(capabilities(response)).containsExactly("MEMORY_RETRIEVAL", "PLANNING", "FINALIZE", "MEMORY_SAVE");
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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.auditTrail().knowledgeBasePlanned()).isFalse();
        assertThat(response.auditTrail().businessToolPlanned()).isFalse();
        AgentStepRecord planningStep = findStep(response, "PLANNING");
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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.auditTrail().maxIterationsExceeded()).isTrue();
        assertThat(capabilities(response))
                .containsExactly("MEMORY_RETRIEVAL", "PLANNING", "KNOWLEDGE_BASE", "FINALIZE", "MEMORY_SAVE");
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
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.auditTrail().timedOut()).isTrue();
        assertThat(capabilities(response)).containsExactly("MEMORY_RETRIEVAL", "PLANNING", "FINALIZE", "MEMORY_SAVE");
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

        assertThatThrownBy(() -> service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION))
                .isInstanceOf(LlmIntegrationException.class);
    }

    // --- conversation memory: history threading ---

    @Test
    void conversationHistoryIsThreadedIntoEveryLlmCallInTheWorkflow() {
        // Mirrors the exact multi-turn scenario from the Phase 10 spec: turn
        // one established "order 12345"; this turn asks about "its payment
        // status" with no order number repeated. Each LLM call - planning,
        // the tool round, and finalize - can only resolve "its" if the prior
        // turn's messages are actually present in the prompt it receives.
        // withHistory() builds a NEW Prompt object whenever history is
        // non-empty (it splices messages into a new list), so stubs below
        // are matched by content via thenAnswer rather than by the exact
        // Prompt instance returned from PromptBuilder - object-identity
        // stubbing only works when history is empty, as in every other test
        // in this class.
        List<Message> history = List.of(
                new UserMessage("My order is 12345."),
                new AssistantMessage("I found order 12345."));
        when(chatMemory.get(CONVERSATION_ID)).thenReturn(history);

        // Each fixture prompt mirrors the real [system, user] shape every
        // PromptBuilder method actually produces - withHistory() treats
        // index 0 as "keep first" and appends everything from index 1
        // onward after the spliced history, so a single-message prompt here
        // would misrepresent how it behaves in production.
        String question = "What is its payment status?";
        Prompt planningPrompt = new Prompt(List.of(new SystemMessage("planning-system"), new UserMessage("planning-marker")));
        when(agentPlanConverter.formatInstructions()).thenReturn("format instructions");
        when(promptBuilder.buildAgentPlanningPrompt(eq(question), eq("format instructions"))).thenReturn(planningPrompt);
        when(agentPlanConverter.parse("raw-plan-json"))
                .thenReturn(new AgentPlan("follow-up about a previously mentioned order", false, true));

        Prompt toolsPrompt = new Prompt(List.of(new SystemMessage("tools-system"), new UserMessage("tools-marker")));
        when(promptBuilder.buildToolsSupportPrompt(question)).thenReturn(toolsPrompt);

        Prompt finalPrompt = new Prompt(List.of(new SystemMessage("final-system"), new UserMessage("final-marker")));
        when(promptBuilder.buildAgentFinalPrompt(eq(question), anyString())).thenReturn(finalPrompt);

        // Both generate() calls (planning, finalize) are distinguished by
        // which base prompt's marker text is still present as the LAST
        // message - withHistory() always preserves the original user
        // message(s) at the end of the spliced list.
        when(llmClientService.generate(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String lastMessage = lastMessageText(prompt);
            return lastMessage.equals("planning-marker") ? "raw-plan-json" : "The payment is successful.";
        });
        when(llmClientService.generateWithTools(any(Prompt.class), eq(supportTools)))
                .thenReturn("The payment is successful.");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, question);

        assertThat(response.answer()).isEqualTo("The payment is successful.");

        ArgumentCaptor<Prompt> toolPromptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(llmClientService).generateWithTools(toolPromptCaptor.capture(), eq(supportTools));
        assertThat(allMessageText(toolPromptCaptor.getValue())).anyMatch(text -> text.contains("12345"));
    }

    private static String lastMessageText(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        return instructions.get(instructions.size() - 1).getText();
    }

    private static List<String> allMessageText(Prompt prompt) {
        return prompt.getInstructions().stream().map(Message::getText).toList();
    }

    @Test
    void successfulTurnIsSavedToConversationMemory() {
        stubPlanning(new AgentPlan("simple", false, false));
        stubFinalize("Hello!");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        ArgumentCaptor<List<Message>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq(CONVERSATION_ID), savedCaptor.capture());
        List<Message> saved = savedCaptor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getText()).isEqualTo(QUESTION);
        assertThat(saved.get(1).getText()).isEqualTo("Hello!");
    }

    @Test
    void conversationHistoryRetrievalFailureDegradesGracefully() {
        when(chatMemory.get(CONVERSATION_ID)).thenThrow(new RuntimeException("Redis unavailable"));
        stubPlanning(new AgentPlan("simple", false, false));
        stubFinalize("Hello! (without memory this time)");

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.answer()).isEqualTo("Hello! (without memory this time)");
        AgentStepRecord memoryStep = findStep(response, "MEMORY_RETRIEVAL");
        assertThat(memoryStep.success()).isFalse();
    }

    @Test
    void conversationMemorySaveFailureDoesNotFailTheRequest() {
        stubPlanning(new AgentPlan("simple", false, false));
        stubFinalize("Hello!");
        doThrow(new RuntimeException("Redis unavailable"))
                .when(chatMemory).add(eq(CONVERSATION_ID), anyList());

        AgentServiceImpl service = newService(DEFAULT_AGENT_PROPERTIES);
        AgentResponse response = service.handle(CUSTOMER_ID, CONVERSATION_ID, QUESTION);

        assertThat(response.answer()).isEqualTo("Hello!");
        AgentStepRecord saveStep = findStep(response, "MEMORY_SAVE");
        assertThat(saveStep.success()).isFalse();
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
