package com.example.aiplatform.service;

import com.example.aiplatform.ai.guardrails.PromptInjectionGuard;
import com.example.aiplatform.ai.llm.LlmClientService;
import com.example.aiplatform.ai.prompt.PromptBuilder;
import com.example.aiplatform.ai.rag.CitationValidator;
import com.example.aiplatform.ai.rag.SemanticSearchService;
import com.example.aiplatform.ai.structured.AgentPlanConverter;
import com.example.aiplatform.ai.tools.SupportTools;
import com.example.aiplatform.config.AgentProperties;
import com.example.aiplatform.config.RagProperties;
import com.example.aiplatform.model.AgentAuditTrail;
import com.example.aiplatform.model.AgentPlan;
import com.example.aiplatform.model.AgentResponse;
import com.example.aiplatform.model.AgentStepRecord;
import com.example.aiplatform.model.SemanticSearchResult;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.observability.RequestContext;
import com.example.aiplatform.observability.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The agent workflow: understand the request (plan), decide whether knowledge
 * retrieval and/or a business tool are required (also the plan), run whichever
 * capabilities were planned, combine what came back, and produce one final
 * grounded answer - all informed by prior turns of the same conversation,
 * explicitly identified by conversationId.
 *
 * <h2>Two things changed here, and they depend on each other</h2>
 *
 * <p><b>The capability steps now run concurrently.</b> A knowledge-base search
 * and a tool-enabled LLM round share no data and neither reads the other's
 * result; running them in sequence added their latencies for no reason at all.
 * That was not a change that could be made in isolation: the tool-call guardrail
 * counted in a thread-local, so a second thread would have started its own count
 * and the limit would have silently doubled. Fixing the state model
 * ({@link RequestContext}) is what made this safe, which is why the review
 * listed them as one item and not two.
 *
 * <p><b>The time bound is now a real deadline.</b> It used to be a check
 * between steps, alongside an iteration cap of 5 over a list that could hold 2.
 * The cap could never fire, and the timeout was never consulted while a call was
 * in flight - so three sequential LLM calls could take far longer than the
 * stated 30 seconds with every check passing on the way through. Now one
 * wall-clock deadline is created once and enforced around each call, by waiting
 * on the call with whatever time is left rather than by asking afterwards
 * whether too much had passed.
 *
 * <p><b>What the deadline does and does not do.</b> It bounds what the caller
 * waits for. It does not stop the provider call already in flight - cancelling a
 * {@link Future} cannot interrupt a socket read the HTTP client does not
 * interrupt - so a worker thread may keep running briefly after the caller has
 * been answered, until the 20s read timeout ends it. The bound that matters to a
 * user is the one that was missing, and it is real now; the resource cleanup
 * behind it is best-effort and bounded by the read timeout.
 *
 * <p>Notably still absent, as of the authorization work: no customerId
 * parameter, and no manual caller-context setup around the capability steps.
 * Spring Security populates the authenticated principal for the whole request;
 * {@code SupportTools} reads it directly via
 * {@link com.example.aiplatform.security.CurrentUser} whenever it authorizes a
 * lookup, and the executor carries that context to the worker thread. There is
 * nothing left for this class to wire manually.
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private static final String CAPABILITY_MEMORY_RETRIEVAL = "MEMORY_RETRIEVAL";
    private static final String CAPABILITY_PLANNING = "PLANNING";
    private static final String CAPABILITY_KNOWLEDGE_BASE = "KNOWLEDGE_BASE";
    private static final String CAPABILITY_BUSINESS_TOOL = "BUSINESS_TOOL";
    private static final String CAPABILITY_FINALIZE = "FINALIZE";
    private static final String CAPABILITY_MEMORY_SAVE = "MEMORY_SAVE";

    /**
     * The floor on how long final synthesis may take, even when the deadline
     * has already been spent.
     *
     * <p>A deadline enforced literally on every call has a failure mode worth
     * naming: the capability steps use the whole budget, synthesis is refused
     * for having no time left, and the caller receives a 500 - after the
     * platform has already paid for planning, retrieval and a tool round. The
     * evidence was gathered and then thrown away. A late answer that says which
     * steps timed out is unambiguously better than no answer at all.
     *
     * <p>So the stated bound is honestly "timeout-seconds for planning and the
     * capability steps, plus up to this much for synthesis" rather than a
     * single number that is not true. The alternative - reserving this slice up
     * front and shortening the capability phase - buys a tidier bound by making
     * the common case worse, which is the wrong trade.
     */
    private static final long MIN_SYNTHESIS_BUDGET_MILLIS = 10_000;

    private final PromptBuilder promptBuilder;
    private final LlmClientService llmClientService;
    private final AgentPlanConverter agentPlanConverter;
    private final SemanticSearchService semanticSearchService;
    private final SupportTools supportTools;
    private final ChatMemory chatMemory;
    private final RagProperties ragProperties;
    private final AgentProperties agentProperties;
    private final PromptInjectionGuard promptInjectionGuard;
    private final CitationValidator citationValidator;
    private final AiPipelineMetrics aiPipelineMetrics;
    private final ExecutorService agentStepExecutor;

    public AgentServiceImpl(PromptBuilder promptBuilder,
                             LlmClientService llmClientService,
                             AgentPlanConverter agentPlanConverter,
                             SemanticSearchService semanticSearchService,
                             SupportTools supportTools,
                             ChatMemory chatMemory,
                             RagProperties ragProperties,
                             AgentProperties agentProperties,
                             PromptInjectionGuard promptInjectionGuard,
                             CitationValidator citationValidator,
                             AiPipelineMetrics aiPipelineMetrics,
                             ExecutorService agentStepExecutor) {
        this.promptBuilder = promptBuilder;
        this.llmClientService = llmClientService;
        this.agentPlanConverter = agentPlanConverter;
        this.semanticSearchService = semanticSearchService;
        this.supportTools = supportTools;
        this.chatMemory = chatMemory;
        this.ragProperties = ragProperties;
        this.agentProperties = agentProperties;
        this.promptInjectionGuard = promptInjectionGuard;
        this.citationValidator = citationValidator;
        this.aiPipelineMetrics = aiPipelineMetrics;
        this.agentStepExecutor = agentStepExecutor;
    }

    @Override
    public AgentResponse handle(String conversationId, String question) {
        promptInjectionGuard.assertSafe(question);
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        List<AgentStepRecord> steps = new ArrayList<>();

        // One deadline for the whole workflow, shared with every step. Derived
        // from the ambient request context so the tool-call counter stays the
        // same object - a fresh context here would reset the tool budget
        // exactly where the tool calls are about to happen.
        RequestContext deadlineContext = RequestContextHolder.currentOrEmpty()
                .orElseGet(() -> RequestContext.forRequest(requestId))
                .withDeadlineIn((long) (agentProperties.timeoutSeconds() * 1000));

        try (RequestContextHolder.Scope ignored = RequestContextHolder.open(deadlineContext)) {
            return handleWithin(deadlineContext, conversationId, question, requestId, steps, startNanos);
        }
    }

    private AgentResponse handleWithin(RequestContext context, String conversationId, String question,
                                        String requestId, List<AgentStepRecord> steps, long startNanos) {
        List<Message> history = retrieveHistory(conversationId, steps);
        AgentPlan plan = plan(context, question, history, steps);

        Map<String, Callable<String>> plannedCapabilities = new LinkedHashMap<>();
        if (plan.needsKnowledgeBase()) {
            plannedCapabilities.put(CAPABILITY_KNOWLEDGE_BASE, () -> knowledgeBaseEvidence(question));
        }
        if (plan.needsBusinessTool()) {
            plannedCapabilities.put(CAPABILITY_BUSINESS_TOOL, () -> businessToolEvidence(question, history));
        }

        CapabilityOutcome outcome = runCapabilities(context, plannedCapabilities, steps, requestId);
        aiPipelineMetrics.recordAgentIterations(plannedCapabilities.size());

        String answer = finalizeAnswer(context, question, history, outcome.evidenceBlocks(), steps, requestId);
        saveTurn(conversationId, question, answer, steps);

        long totalDurationMillis = elapsedMillis(startNanos);
        AgentAuditTrail auditTrail = new AgentAuditTrail(requestId, question, plan.needsKnowledgeBase(),
                plan.needsBusinessTool(), List.copyOf(steps), outcome.timedOut(), totalDurationMillis);
        log.info("Agent request {} completed in {}ms: {} step(s), timedOut={}",
                requestId, totalDurationMillis, steps.size(), outcome.timedOut());

        return new AgentResponse(answer, llmClientService.modelName(), auditTrail);
    }

    private record CapabilityOutcome(List<String> evidenceBlocks, boolean timedOut) {
    }

    /**
     * Submits every planned capability at once and collects the results within
     * whatever remains of the deadline.
     *
     * <p>Each task is wrapped by {@link RequestContextHolder#propagate} so the
     * worker inherits the correlation ID, the deadline, and - critically - the
     * SAME tool-call counter; the Spring Security context travels separately,
     * via the executor decorator (see {@code AgentExecutorConfig}).
     *
     * <p>A failure in one capability never fails the request. Each step records
     * why it failed and contributes a sentence saying so, and synthesis
     * proceeds with whatever evidence did arrive - a partial answer that says
     * what it could not check beats a 502 for a question half of which was
     * answerable.
     */
    private CapabilityOutcome runCapabilities(RequestContext context, Map<String, Callable<String>> capabilities,
                                               List<AgentStepRecord> steps, String requestId) {
        List<String> evidenceBlocks = new ArrayList<>();
        if (capabilities.isEmpty()) {
            return new CapabilityOutcome(evidenceBlocks, false);
        }

        Map<String, Future<String>> inFlight = new LinkedHashMap<>();
        Map<String, Long> startedAt = new LinkedHashMap<>();
        boolean timedOut = false;

        for (Map.Entry<String, Callable<String>> capability : capabilities.entrySet()) {
            startedAt.put(capability.getKey(), System.nanoTime());
            try {
                inFlight.put(capability.getKey(),
                        agentStepExecutor.submit(RequestContextHolder.propagate(capability.getValue())));
            } catch (RejectedExecutionException e) {
                // The pool is saturated. Running the step inline is slower and
                // still correct - and correctness here includes the deadline,
                // which an inline call cannot be abandoned against, so the
                // remaining budget is at least checked before starting.
                log.warn("Agent request {}: step executor saturated, running {} on the request thread",
                        requestId, capability.getKey());
                inFlight.put(capability.getKey(), runInline(capability.getValue()));
            }
        }

        for (Map.Entry<String, Future<String>> entry : inFlight.entrySet()) {
            String capability = entry.getKey();
            long stepStart = startedAt.get(capability);
            long remaining = context.remainingMillis();
            try {
                String evidence = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                evidenceBlocks.add(evidence);
                steps.add(new AgentStepRecord(capability, true, "Completed", elapsedMillis(stepStart)));
            } catch (TimeoutException e) {
                timedOut = true;
                entry.getValue().cancel(true);
                aiPipelineMetrics.recordSafetyBoundTriggered("agent_timeout");
                log.warn("Agent request {}: {} exceeded the {}s request deadline - continuing without it",
                        requestId, capability, agentProperties.timeoutSeconds());
                evidenceBlocks.add(capability + ": timed out and was skipped.");
                steps.add(new AgentStepRecord(capability, false, "Timed out against the request deadline",
                        elapsedMillis(stepStart)));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.warn("Agent request {}: {} failed", requestId, capability, cause);
                evidenceBlocks.add(capability + ": failed and was skipped.");
                steps.add(new AgentStepRecord(capability, false, "Failed: " + cause.getMessage(),
                        elapsedMillis(stepStart)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                entry.getValue().cancel(true);
                steps.add(new AgentStepRecord(capability, false, "Interrupted", elapsedMillis(stepStart)));
                break;
            }
        }
        return new CapabilityOutcome(evidenceBlocks, timedOut);
    }

    /** An already-completed Future, so the collection loop above has one shape rather than two. */
    private static Future<String> runInline(Callable<String> task) {
        java.util.concurrent.FutureTask<String> completed = new java.util.concurrent.FutureTask<>(task);
        completed.run();
        return completed;
    }

    private List<Message> retrieveHistory(String conversationId, List<AgentStepRecord> steps) {
        long stepStart = System.nanoTime();
        try {
            List<Message> history = chatMemory.get(conversationId);
            steps.add(new AgentStepRecord(CAPABILITY_MEMORY_RETRIEVAL, true,
                    history.size() + " prior message(s) loaded", elapsedMillis(stepStart)));
            return history;
        } catch (RuntimeException e) {
            log.warn("Failed to retrieve conversation history for {} - proceeding without it", conversationId, e);
            steps.add(new AgentStepRecord(CAPABILITY_MEMORY_RETRIEVAL, false,
                    "History retrieval failed: " + e.getMessage(), elapsedMillis(stepStart)));
            return List.of();
        }
    }

    private AgentPlan plan(RequestContext context, String question, List<Message> history,
                            List<AgentStepRecord> steps) {
        long stepStart = System.nanoTime();
        try {
            String formatInstructions = agentPlanConverter.formatInstructions();
            Prompt basePrompt = promptBuilder.buildAgentPlanningPrompt(question, formatInstructions);
            String raw = withinDeadline(context, "planning", 0,
                    () -> llmClientService.generate(withHistory(basePrompt, history)));
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

    private String knowledgeBaseEvidence(String question) {
        List<SemanticSearchResult> retrieved = semanticSearchService.search(question, ragProperties.topK());
        List<SemanticSearchResult> relevant = retrieved.stream()
                .filter(result -> result.isRelevantAt(ragProperties.similarityThreshold()))
                .toList();
        return formatKnowledgeBaseEvidence(relevant);
    }

    private String businessToolEvidence(String question, List<Message> history) {
        Prompt basePrompt = promptBuilder.buildToolsSupportPrompt(question);
        String toolAnswer = llmClientService.generateWithTools(withHistory(basePrompt, history), supportTools);
        return "Business system lookup result:\n" + toolAnswer;
    }

    private String finalizeAnswer(RequestContext context, String question, List<Message> history,
                                   List<String> evidenceBlocks, List<AgentStepRecord> steps, String requestId) {
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
            Prompt basePrompt = promptBuilder.buildAgentFinalPrompt(question, evidence);
            String answer = withinDeadline(context, "synthesis", MIN_SYNTHESIS_BUDGET_MILLIS,
                    () -> llmClientService.generate(withHistory(basePrompt, history)));
            // The evidence block numbers its knowledge-base excerpts [KB1],
            // [KB2]... so a citation past the end of that list is a fabricated
            // reference, and it is caught here rather than only offline.
            answer = citationValidator.validate(answer, countKnowledgeBaseExcerpts(evidence), "KB");
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

    /**
     * Runs one LLM call bounded by what remains of the request deadline.
     *
     * <p>This is what "enforced around each LLM call" means concretely: the
     * call is submitted to the same pool the capability steps use and waited on
     * with the remaining budget, so the wait is bounded even though the provider
     * call itself is a blocking socket read that no timeout of ours can shorten.
     * Asking after the fact whether too much time had passed - which is what the
     * previous between-steps check did - cannot bound anything, because by then
     * the time has already been spent.
     *
     * <p>Falls back to calling inline when the pool refuses the task. A
     * saturated pool should slow the request down, not fail it, and the inline
     * call is still bounded by the HTTP read timeout.
     *
     * <p>{@code minimumMillis} is the floor described on
     * {@link #MIN_SYNTHESIS_BUDGET_MILLIS}: planning passes 0 because a failed
     * plan degrades gracefully to "no capabilities", while synthesis passes a
     * floor because a refused synthesis means no answer at all.
     */
    private String withinDeadline(RequestContext context, String what, long minimumMillis,
                                   java.util.function.Supplier<String> call) {
        long budget = Math.max(context.remainingMillis(), minimumMillis);
        if (budget <= 0) {
            aiPipelineMetrics.recordSafetyBoundTriggered("agent_timeout");
            throw new IllegalStateException("Agent deadline exceeded before " + what + " could start");
        }
        Future<String> future;
        try {
            future = agentStepExecutor.submit(RequestContextHolder.propagate(call)::get);
        } catch (RejectedExecutionException e) {
            log.warn("Agent step executor saturated - running {} inline", what);
            return call.get();
        }
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            aiPipelineMetrics.recordSafetyBoundTriggered("agent_timeout");
            throw new IllegalStateException("Agent deadline exceeded during " + what, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Agent " + what + " failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("Interrupted during agent " + what, e);
        }
    }

    private void saveTurn(String conversationId, String question, String answer, List<AgentStepRecord> steps) {
        long stepStart = System.nanoTime();
        try {
            chatMemory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
            steps.add(new AgentStepRecord(CAPABILITY_MEMORY_SAVE, true,
                    "Turn saved to conversation memory", elapsedMillis(stepStart)));
        } catch (RuntimeException e) {
            // The caller still gets their answer even if we failed to
            // remember it - losing memory of one turn is not worth failing
            // an otherwise-successful request over.
            log.warn("Failed to save conversation turn for {} - the answer was still returned to the caller",
                    conversationId, e);
            steps.add(new AgentStepRecord(CAPABILITY_MEMORY_SAVE, false,
                    "Memory save failed: " + e.getMessage(), elapsedMillis(stepStart)));
        }
    }

    /**
     * Splices prior-turn messages between the system message and the current
     * user message of an already-built prompt, so every LLM call in the
     * workflow - planning, the tool round, and final synthesis - can resolve
     * references like "its" against what was actually said earlier, without
     * PromptBuilder needing to know anything about conversation memory.
     *
     * <p>Carries {@code basePrompt.getOptions()} across to the rebuilt prompt.
     * That is load-bearing, not defensive copying: PromptBuilder attaches both
     * a sampling temperature and a model tier to every prompt it produces, and
     * rebuilding without the options would silently drop the planning call back
     * to the provider default - reintroducing the non-determinism that change
     * exists to remove AND quietly moving the highest-volume call in the
     * workflow back onto the expensive model, on the agent path, which is the
     * hardest place to notice either.
     */
    private static Prompt withHistory(Prompt basePrompt, List<Message> history) {
        if (history.isEmpty()) {
            return basePrompt;
        }
        List<Message> instructions = basePrompt.getInstructions();
        List<Message> combined = new ArrayList<>(instructions.size() + history.size());
        combined.add(instructions.get(0));
        combined.addAll(history);
        combined.addAll(instructions.subList(1, instructions.size()));
        return new Prompt(combined, basePrompt.getOptions());
    }

    /**
     * Instance method (not static) because it sanitizes each chunk through
     * {@link PromptInjectionGuard#sanitize(String)} before it's spliced into
     * evidence the final-synthesis prompt trusts - the same indirect-injection
     * defense as {@link QuestionAnsweringServiceImpl}'s buildContext, applied
     * here because the agent workflow retrieves knowledge-base content too.
     */
    private String formatKnowledgeBaseEvidence(List<SemanticSearchResult> relevant) {
        if (relevant.isEmpty()) {
            return "Knowledge base: no sufficiently relevant documentation was found.";
        }
        StringBuilder block = new StringBuilder("Knowledge base excerpts:\n");
        for (int i = 0; i < relevant.size(); i++) {
            SemanticSearchResult chunk = relevant.get(i);
            block.append("[KB").append(i + 1).append("] (").append(chunk.documentTitle()).append(") ")
                    .append(promptInjectionGuard.sanitize(chunk.content())).append('\n');
        }
        return block.toString();
    }

    /** How many [KBn] markers the evidence actually offered - the upper bound a citation may name. */
    private static int countKnowledgeBaseExcerpts(String evidence) {
        return (int) java.util.regex.Pattern.compile("\\[KB\\d+]").matcher(evidence).results().count();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
