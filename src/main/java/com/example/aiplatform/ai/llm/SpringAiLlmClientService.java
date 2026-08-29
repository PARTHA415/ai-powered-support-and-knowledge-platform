package com.example.aiplatform.ai.llm;

import com.example.aiplatform.ai.guardrails.SensitiveDataGuard;
import com.example.aiplatform.ai.guardrails.TokenBudgetGuard;
import com.example.aiplatform.config.GuardrailProperties;
import com.example.aiplatform.config.ModelTierProperties;
import com.example.aiplatform.exception.LlmIntegrationException;
import com.example.aiplatform.exception.PromptTooLargeException;
import com.example.aiplatform.exception.ToolPolicyExceptions;
import com.example.aiplatform.observability.AiPipelineMetrics;
import com.example.aiplatform.observability.CostMeter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Every LLM call in this application - chat, structured output, RAG, tool
 * calling, agent planning, agent finalization - flows through exactly this
 * class, because they all go through the {@link LlmClientService} seam
 * rather than touching Spring AI's {@code ChatClient} directly. That makes
 * this the single choke point for the guardrails and measurements that need
 * to apply uniformly to every LLM interaction regardless of which business
 * feature triggered it:
 *
 * <ul>
 *   <li>a hard prompt-size ceiling before the call (Phase 12);</li>
 *   <li>the caller's token budget, checked before and charged after
 *       ({@link TokenBudgetGuard});</li>
 *   <li>sensitive-data/system-prompt-leak scrubbing on the response;</li>
 *   <li>token usage and spend, recorded per model and per tier
 *       ({@link CostMeter});</li>
 *   <li>a circuit breaker (the {@code llm} instance, configured in
 *       application.yml) protecting every caller from a sustained provider
 *       outage.</li>
 * </ul>
 *
 * <p>The same "enforced once, inherited everywhere" property Phase 11
 * established for tool authorization.
 *
 * <p><b>Why {@code .chatResponse()} rather than {@code .content()}.</b> The
 * convenience method returns only text and discards the response metadata,
 * which is where the token counts live. Cost cannot be metered from text.
 * Taking the full response costs nothing and is what makes every number in
 * {@link CostMeter} possible; the text is one accessor away.
 */
@Service
public class SpringAiLlmClientService implements LlmClientService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClientService.class);

    /** Rough heuristic, not a real tokenizer - see the Phase 12 docs for why. */
    private static final int CHARS_PER_ESTIMATED_TOKEN = 4;

    private final ChatClient chatClient;
    private final GuardrailProperties guardrailProperties;
    private final SensitiveDataGuard sensitiveDataGuard;
    private final AiPipelineMetrics aiPipelineMetrics;
    private final ModelTierProperties modelTierProperties;
    private final CostMeter costMeter;
    private final TokenBudgetGuard tokenBudgetGuard;

    public SpringAiLlmClientService(ChatClient.Builder chatClientBuilder,
                                     GuardrailProperties guardrailProperties,
                                     SensitiveDataGuard sensitiveDataGuard,
                                     AiPipelineMetrics aiPipelineMetrics,
                                     ModelTierProperties modelTierProperties,
                                     CostMeter costMeter,
                                     TokenBudgetGuard tokenBudgetGuard) {
        this.chatClient = chatClientBuilder.build();
        this.guardrailProperties = guardrailProperties;
        this.sensitiveDataGuard = sensitiveDataGuard;
        this.aiPipelineMetrics = aiPipelineMetrics;
        this.modelTierProperties = modelTierProperties;
        this.costMeter = costMeter;
        this.tokenBudgetGuard = tokenBudgetGuard;
    }

    @Override
    public String modelName(ModelTier tier) {
        return modelTierProperties.nameFor(tier);
    }

    @Override
    @CircuitBreaker(name = "llm", fallbackMethod = "generateFallback")
    public String generate(Prompt prompt) {
        beforeCall(prompt);
        log.debug("Sending prompt to LLM ({} messages)", prompt.getInstructions().size());
        try {
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
            return afterCall(prompt, response);
        } catch (Exception e) {
            throw asLlmFailure(e, "LLM call failed");
        }
    }

    @Override
    @CircuitBreaker(name = "llm", fallbackMethod = "generateWithToolsFallback")
    public String generateWithTools(Prompt prompt, Object... tools) {
        beforeCall(prompt);
        log.debug("Sending prompt to LLM with {} tool object(s) ({} messages)",
                tools.length, prompt.getInstructions().size());
        try {
            ChatResponse response = chatClient.prompt(prompt)
                    .tools(tools)
                    .call()
                    .chatResponse();
            return afterCall(prompt, response);
        } catch (Exception e) {
            throw asLlmFailure(e, "LLM call with tools failed");
        }
    }

    @Override
    @CircuitBreaker(name = "llm", fallbackMethod = "generateWithToolCallbackProviderFallback")
    public String generateWithTools(Prompt prompt, ToolCallbackProvider toolCallbackProvider) {
        beforeCall(prompt);
        log.debug("Sending prompt to LLM with a tool callback provider ({} messages)",
                prompt.getInstructions().size());
        try {
            ChatResponse response = chatClient.prompt(prompt)
                    .toolCallbacks(toolCallbackProvider)
                    .call()
                    .chatResponse();
            return afterCall(prompt, response);
        } catch (Exception e) {
            throw asLlmFailure(e, "LLM call with tool callback provider failed");
        }
    }

    /**
     * Streaming generation.
     *
     * <p>Deliberately NOT annotated with {@code @CircuitBreaker}. The
     * annotation's aspect judges a call by whether the <em>method</em> threw,
     * and this method returns a {@code Flux} immediately and successfully even
     * when the provider is about to fail on the first element - so the
     * annotation would report every streaming call as a success and quietly
     * corrupt the breaker's failure rate for the non-streaming callers sharing
     * it. Guarding a reactive return properly needs the reactive operator, not
     * the aspect. The pre-call guardrails below still apply, and they are the
     * ones that protect the provider from us rather than us from the provider.
     *
     * <p><b>The output guardrail cannot be applied here, and that is a real
     * trade.</b> {@link SensitiveDataGuard#sanitizeOutput} inspects a complete
     * response; a token already written to the socket cannot be unwritten.
     * Per-chunk scrubbing would be worse than none, because a redaction pattern
     * split across two chunks matches neither. So streaming is offered on the
     * open-ended chat path, whose responses are model prose rather than
     * retrieved records, and the endpoints that return customer data keep the
     * buffered path where the guard still runs. Streaming a
     * sensitive-data-bearing response safely needs an incremental scrubber with
     * a lookbehind window - real, but a larger piece of work than this, and
     * shipping the fast path first while saying plainly what it does not cover
     * beats shipping neither.
     */
    @Override
    public Flux<String> generateStream(Prompt prompt) {
        beforeCall(prompt);
        log.debug("Streaming prompt to LLM ({} messages)", prompt.getInstructions().size());
        ModelTier tier = tierOf(prompt);
        // Captured HERE, on the request thread. The stream completes on a
        // reactor thread long after the servlet thread has been released, where
        // there is no security context left to read the caller from.
        String streamCaller = tokenBudgetGuard.currentCaller();
        return chatClient.prompt(prompt)
                .stream()
                .chatResponse()
                // Usage arrives on the final chunk, so metering happens as the
                // stream completes rather than before it starts. A stream the
                // client abandons half-way is therefore under-metered - the
                // provider still billed it. Accepted: an abandoned stream is
                // rare next to the value of metering the ones that finish.
                .doOnNext(response -> recordUsage(response, tier, streamCaller))
                .map(SpringAiLlmClientService::textOf)
                .filter(text -> !text.isEmpty())
                .onErrorMap(throwable -> {
                    if (ToolPolicyExceptions.isPolicyViolation(throwable) && throwable instanceof RuntimeException) {
                        return throwable;
                    }
                    log.error("Streaming LLM call failed", throwable);
                    return new LlmIntegrationException("Failed to get a response from the LLM", throwable);
                });
    }

    /**
     * Everything that must happen before a prompt reaches the provider, in the
     * order it must happen: reject an oversized prompt without spending
     * anything on it, then reject a caller who has already spent their budget.
     * Both are cheap, local, and deterministic - neither involves the provider.
     */
    private void beforeCall(Prompt prompt) {
        assertWithinTokenBudget(prompt);
        tokenBudgetGuard.assertWithinBudget();
    }

    /** Meter what the call consumed, then scrub what it produced. */
    private String afterCall(Prompt prompt, ChatResponse response) {
        recordUsage(response, tierOf(prompt));
        String text = textOf(response);
        log.debug("Received LLM response ({} chars)", text.length());
        return sensitiveDataGuard.sanitizeOutput(text);
    }

    /**
     * <p><b>A caveat worth stating.</b> On a tool-calling round the provider is
     * invoked more than once behind one {@code call()}, and what arrives here
     * is the final response. Depending on the provider integration its usage
     * may cover only that last exchange rather than the whole loop, which would
     * make tool-heavy requests under-reported. The counts are still the real
     * ones the provider sent - nothing is invented - and the direction of the
     * error is known and stated rather than assumed away.
     */
    private void recordUsage(ChatResponse response, ModelTier tier) {
        recordUsage(response, tier, tokenBudgetGuard.currentCaller());
    }

    private void recordUsage(ChatResponse response, ModelTier tier, String caller) {
        try {
            LlmCallUsage usage = LlmCallUsage.from(response, tier);
            costMeter.record(usage);
            tokenBudgetGuard.record(usage, caller);
        } catch (RuntimeException e) {
            // Metering must never fail the request it was measuring.
            log.warn("Failed to record LLM usage", e);
        }
    }

    /**
     * Which tier this prompt asked for, read back from the model name the
     * prompt builder attached. Derived rather than passed as a parameter so the
     * {@link LlmClientService} seam keeps its shape: the prompt already carries
     * everything about how it wants to be executed, and adding a tier argument
     * to four methods would let a call site set a temperature for one tier and
     * a model for another.
     */
    private ModelTier tierOf(Prompt prompt) {
        String requested = prompt.getOptions() == null ? null : prompt.getOptions().getModel();
        return requested != null && requested.equalsIgnoreCase(modelTierProperties.fast())
                ? ModelTier.FAST
                : ModelTier.CAPABLE;
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * Invoked instead of the method body once the {@code llm} circuit
     * breaker is OPEN (sustained failures already observed - see
     * application.yml) - fails fast with a clear, honest message rather
     * than letting every request queue up behind a provider that's already
     * down. {@code PromptTooLargeException} from {@code assertWithinTokenBudget}
     * is thrown from inside this same annotated method, so without
     * application.yml's {@code ignore-exceptions} entry for it, a caller
     * repeatedly sending oversized prompts would trip the SAME breaker a
     * real provider outage does - a guardrail rejection has nothing to do
     * with whether the LLM provider is healthy, and must not be allowed to
     * count against it. The same applies to a caller exhausting their token
     * budget.
     */
    private String generateFallback(Prompt prompt, Throwable cause) {
        return circuitOpenFallback(cause);
    }

    private String generateWithToolsFallback(Prompt prompt, Object[] tools, Throwable cause) {
        return circuitOpenFallback(cause);
    }

    private String generateWithToolCallbackProviderFallback(Prompt prompt, ToolCallbackProvider toolCallbackProvider,
                                                              Throwable cause) {
        return circuitOpenFallback(cause);
    }

    /**
     * Resilience4j's {@code fallbackMethod} is invoked for EVERY exception
     * leaving the annotated method, not only for a rejected call - and since
     * these fallbacks declare a plain {@link Throwable} parameter, they match
     * everything. Rewriting all of it as "the provider is down" would be
     * wrong, and in several cases actively misleading:
     *
     * <ul>
     *   <li>{@link PromptTooLargeException} from {@code assertWithinTokenBudget}
     *       is a guardrail rejecting an oversized request. It maps to 413, and
     *       the {@code ignore-exceptions} entry in application.yml already
     *       keeps it from counting against the breaker - but without the check
     *       below it never reached its handler anyway, arriving at the caller
     *       as a 502 claiming the AI service was unavailable.</li>
     *   <li>A tool-policy violation ({@link ToolPolicyExceptions#RETHROWN}) is
     *       this application's own decision about its own data. Reporting an
     *       authorization denial as a provider outage would be a lie to the
     *       caller and a false alarm to whoever is on call.</li>
     *   <li>A token-budget rejection is a statement about one caller's
     *       spending, not about the provider.</li>
     * </ul>
     *
     * <p>So only {@link CallNotPermittedException} - thrown when the breaker
     * is genuinely OPEN and refused the call - produces the unavailable
     * message. Everything else already carries its own meaning and is
     * propagated unchanged.
     */
    private String circuitOpenFallback(Throwable cause) {
        if (cause instanceof CallNotPermittedException) {
            log.error("LLM circuit breaker is OPEN - failing fast while the provider recovers", cause);
            throw new LlmIntegrationException(
                    "The AI service is temporarily unavailable and is being given time to recover "
                            + "(circuit breaker open) - please try again shortly.", cause);
        }
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new LlmIntegrationException("Failed to get a response from the LLM", cause);
    }

    /**
     * Turns a failure from the provider call into the right exception - with
     * one deliberate exemption.
     *
     * <p>A tool-policy violation ({@link ToolPolicyExceptions#RETHROWN}) can
     * now propagate out of the tool-calling loop, because
     * {@link com.example.aiplatform.config.ToolExecutionConfig} configures
     * Spring AI to rethrow those instead of feeding them back to the model.
     * They arrive here as ordinary exceptions from
     * {@code chatClient.prompt(...).call()}, and blanket-wrapping them as
     * {@link LlmIntegrationException} would undo the rethrow entirely: an
     * authorization denial or an exceeded tool-call budget would surface as a
     * 502 Bad Gateway blaming the LLM provider for a decision this
     * application made about its own data. They are re-thrown unchanged so
     * {@code GlobalExceptionHandler} maps them to their real status.
     *
     * <p>Checked against the whole cause chain rather than the top-level type,
     * since a provider integration may wrap what a tool threw before it gets
     * back here.
     *
     * <p>Everything else is a genuine provider failure and is logged and
     * wrapped as before. Declared as returning the exception so call sites
     * read {@code throw asLlmFailure(...)}, which makes it obvious to the
     * compiler and the reader that control does not continue.
     */
    private static RuntimeException asLlmFailure(Exception e, String logMessage) {
        if (ToolPolicyExceptions.isPolicyViolation(e)) {
            log.warn("{} - propagating a tool-policy violation rather than wrapping it as a provider failure",
                    logMessage);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
        }
        log.error(logMessage, e);
        return new LlmIntegrationException("Failed to get a response from the LLM", e);
    }

    /**
     * Estimates prompt size as total characters / 4 (a common rough
     * approximation for English text) and rejects before ever calling the
     * provider if it exceeds app.guardrails.max-prompt-tokens. Deliberately
     * outside the try/catch above: this is a guardrail rejection of an
     * oversized request, not a provider failure, so it must NOT be wrapped
     * as a 502 LlmIntegrationException - it maps to 413 Payload Too Large.
     */
    private void assertWithinTokenBudget(Prompt prompt) {
        int totalChars = prompt.getInstructions().stream()
                .mapToInt(message -> message.getText() == null ? 0 : message.getText().length())
                .sum();
        int estimatedTokens = totalChars / CHARS_PER_ESTIMATED_TOKEN;
        if (estimatedTokens > guardrailProperties.maxPromptTokens()) {
            aiPipelineMetrics.recordSafetyBoundTriggered("prompt_too_large");
            throw new PromptTooLargeException(
                    "Prompt is too large to send to the LLM: ~" + estimatedTokens + " estimated tokens exceeds "
                            + "the configured limit of " + guardrailProperties.maxPromptTokens()
                            + ". This guards against context-stuffing, e.g. an oversized document or an "
                            + "inflated conversation history.");
        }
    }
}
