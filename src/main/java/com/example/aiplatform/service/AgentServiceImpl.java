package com.example.aiplatform.service;

import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.ai.structured.AgentPlanConverter;
import com.example.aiplatform.ai.tools.CallerContextHolder;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.config.AgentProperties;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.AgentAuditTrail;
import com.example.aiplatform.model.AgentPlan;
import com.example.aiplatform.model.AgentResponse;
import com.example.aiplatform.model.AgentStepRecord;
import com.example.aiplatform.model.SemanticSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Phase 9 agent workflow: understand the request (plan), decide whether
 * knowledge retrieval and/or a business tool are required (also the plan),
 * call whichever capabilities were planned (bounded, never a loop that can
 * run away), combine what came back, and produce one final grounded answer.
 *
 * Deliberately lives in {@code service}, not a new {@code ai/agent} package:
 * its job - composing existing ai/* seams (PromptBuilder, LlmClientService,
 * SemanticSearchService, SupportTools) into one business-facing flow - is
 * identical in kind to ChatServiceImpl, QuestionAnsweringServiceImpl, and
 * SupportAssistantServiceImpl from Phases 1, 6, and 8, so it follows the
 * same precedent rather than introducing a new package for its own sake.
 *
 * The only genuinely iterative, boundable part of this workflow is the
 * capability-execution loop below (at most 2 items in this phase: knowledge
 * base, business tool) - planning and finalizing are each a single bounded
 * call, not something the model can ask to repeat.
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private static final String CAPABILITY_PLANNING = "PLANNING";
    private static final String CAPABILITY_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    private static final String CAPABILITY_BUSINESS_TOOL = "BUSINESS_TOOL";
    private static final String CAPABILITY_FINALIZE = "FINALIZE";

    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final AgentPlanConverter agentPlanConverter;
    private final SemanticSearchService semanticSearchService;
    private final SupportTools supportTools;
    private final RagProperties ragProperties;
    private final AgentProperties agentProperties;
    private final String model;

    public AgentServiceImpl(PromptBuilder promptBuilder,
                             LlmClientService llmClientService,
                             AgentPlanConverter agentPlanConverter,
                             SemanticSearchService semanticSearchService,
                             SupportTools supportTools,
                             RagProperties ragProperties,
                             AgentProperties agentProperties,
                             @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.agentPlanConverter = agentPlanConverter;
        this.semanticSearchService = semanticSearchService;
        this.supportTools = supportTools;
        this.ragProperties = ragProperties;
        this.agentProperties = agentProperties;
        this.model = model;
    }

    @Override
    public AgentResponse handle(String customerId, String question) {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        List<AgentStepRecord> steps = new ArrayList<>();

        AgentPlan plan = plan(question, steps);

        List<String> plannedCapabilities = new ArrayList<>();
        if (plan.needsKnowledgeBase()) {
            plannedCapabilities.add(CAPABILITY_KNOWLEDGE_BASE);
        }
        if (plan.needsBusinessTool()) {
            plannedCapabilities.add(CAPABILITY_BUSINESS_TOOL);
        }

        List<String> evidenceBlocks = new ArrayList<>();
        boolean maxIterationsExceeded = false;
        boolean timedOut = false;

        CallerContextHolder.setCurrentCustomerId(customerId);
        try {
            int iteration = 0;
            for (String capability : plannedCapabilities) {
                iteration++;
                if (iteration > agentProperties.maxIterations()) {
                    maxIterationsExceeded = true;
                    log.warn("Agent request {}: hit max iterations ({}) - stopping with partial evidence",
                            requestId, agentProperties.maxIterations());
                    break;
                }
                if (elapsedSeconds(startNanos) > agentProperties.timeoutSeconds()) {
                    timedOut = true;
                    log.warn("Agent request {}: exceeded timeout ({}s) - stopping with partial evidence",
                            requestId, agentProperties.timeoutSeconds());
                    break;
                }
                if (capability.equals(CAPABILITY_KNOWLEDGE_BASE)) {
                    executeKnowledgeBaseStep(question, evidenceBlocks, steps, requestId);
                } else {
                    executeBusinessToolStep(question, evidenceBlocks, steps, requestId);
                }
            }
        } finally {
            CallerContextHolder.clear();
        }

        String answer = finalizeAnswer(question, evidenceBlocks, steps, requestId);

        long totalDurationMillis = elapsedMillis(startNanos);
        AgentAuditTrail auditTrail = new AgentAuditTrail(requestId, question, plan.needsKnowledgeBase(),
                plan.needsBusinessTool(), List.copyOf(steps), maxIterationsExceeded, timedOut, totalDurationMillis);
        log.info("Agent request {} completed in {}ms: {} step(s), maxIterationsExceeded={}, timedOut={}",
                requestId, totalDurationMillis, steps.size(), maxIterationsExceeded, timedOut);

        return new AgentResponse(answer, model, auditTrail);
    }

    private AgentPlan plan(String question, List<AgentStepRecord> steps) {
        long stepStart = System.nanoTime();
        try {
            String formatInstructions = agentPlanConverter.formatInstructions();
            Prompt prompt = promptBuilder.buildAgentPlanningPrompt(question, formatInstructions);
            String raw = llmClientService.generate(prompt);
            AgentPlan agentPlan = agentPlanConverter.parse(raw);
            steps.add(new AgentStepRecord(CAPABILITY_PLANNING, true,
                    "needsKnowledgeBase=" + agentPlan.needsKnowledgeBase()
                            + ", needsBusinessTool=" + agentPlan.needsBusinessTool()
                            + " - " + agentPlan.reasoning(),
                    elapsedMillis(stepStart)));
            return agentPlan;
        } catch (RuntimeException e) {
            log.warn("Agent planning failed - defaulting to no automated capability selection", e);
            steps.add(new AgentStepRecord(CAPABILITY_PLANNING, false,
                    "Planning failed: " + e.getMessage(), elapsedMillis(stepStart)));
            return new AgentPlan("planning failed, defaulting to no capabilities", false, false);
        }
    }

    private void executeKnowledgeBaseStep(String question, List<String> evidenceBlocks,
                                           List<AgentStepRecord> steps, String requestId) {
        long stepStart = System.nanoTime();
        try {
            List<SemanticSearchResult> retrieved = semanticSearchService.search(question, ragProperties.topK());
            List<SemanticSearchResult> relevant = retrieved.stream()
                    .filter(result -> result.similarity() >= ragProperties.similarityThreshold())
                    .toList();
            evidenceBlocks.add(formatKnowledgeBaseEvidence(relevant));
            steps.add(new AgentStepRecord(CAPABILITY_KNOWLEDGE_BASE, true,
                    relevant.size() + " relevant chunk(s) found", elapsedMillis(stepStart)));
        } catch (RuntimeException e) {
            log.warn("Agent request {}: knowledge base search failed", requestId, e);
            evidenceBlocks.add("Knowledge base: search failed and was skipped.");
            steps.add(new AgentStepRecord(CAPABILITY_KNOWLEDGE_BASE, false,
                    "Search failed: " + e.getMessage(), elapsedMillis(stepStart)));
        }
    }

    private void executeBusinessToolStep(String question, List<String> evidenceBlocks,
                                          List<AgentStepRecord> steps, String requestId) {
        long stepStart = System.nanoTime();
        try {
            Prompt prompt = promptBuilder.buildToolsSupportPrompt(question);
            String toolAnswer = llmClientService.generateWithTools(prompt, supportTools);
            evidenceBlocks.add("Business system lookup result:\n" + toolAnswer);
            steps.add(new AgentStepRecord(CAPABILITY_BUSINESS_TOOL, true,
                    "Tool-enabled LLM call completed", elapsedMillis(stepStart)));
        } catch (RuntimeException e) {
            log.warn("Agent request {}: business tool lookup failed", requestId, e);
            evidenceBlocks.add("Business system lookup: failed and was skipped.");
            steps.add(new AgentStepRecord(CAPABILITY_BUSINESS_TOOL, false,
                    "Tool lookup failed: " + e.getMessage(), elapsedMillis(stepStart)));
        }
    }

    private String finalizeAnswer(String question, List<String> evidenceBlocks,
                                   List<AgentStepRecord> steps, String requestId) {
        long stepStart = System.nanoTime();
        String evidence = evidenceBlocks.isEmpty()
                ? "No additional evidence was gathered for this question."
                : String.join("\n\n", evidenceBlocks);
        // Unlike the optional enrichment steps above, a failure here is not
        // swallowed - there is no reasonable answer left to fall back on if
        // even the final synthesis call fails, so it propagates as a real
        // error (surfaced as 502 Bad Gateway via LlmIntegrationException,
        // same as every other LLM-call failure in this codebase).
        try {
            Prompt prompt = promptBuilder.buildAgentFinalPrompt(question, evidence);
            String answer = llmClientService.generate(prompt);
            steps.add(new AgentStepRecord(CAPABILITY_FINALIZE, true, "Final answer generated",
                    elapsedMillis(stepStart)));
            return answer;
        } catch (RuntimeException e) {
            log.error("Agent request {}: final synthesis failed", requestId, e);
            steps.add(new AgentStepRecord(CAPABILITY_FINALIZE, false,
                    "Final synthesis failed: " + e.getMessage(), elapsedMillis(stepStart)));
            throw e;
        }
    }

    private static String formatKnowledgeBaseEvidence(List<SemanticSearchResult> relevant) {
        if (relevant.isEmpty()) {
            return "Knowledge base: no sufficiently relevant documentation was found.";
        }
        StringBuilder block = new StringBuilder("Knowledge base excerpts:\n");
        for (int i = 0; i < relevant.size(); i++) {
            SemanticSearchResult chunk = relevant.get(i);
            block.append("[KB").append(i + 1).append("] (").append(chunk.documentTitle()).append(") ")
                    .append(chunk.content()).append('\n');
        }
        return block.toString();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static double elapsedSeconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
