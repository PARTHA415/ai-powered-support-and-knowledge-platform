package com.example.aiplatform.ai.prompt;

import com.example.aiplatform.ai.llm.ModelTier;
import com.example.aiplatform.config.ModelTierProperties;
import com.example.aiplatform.config.TemperatureProperties;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Builds every prompt in the application, and decides the {@link ChatOptions}
 * each one carries - now both the sampling temperature AND which
 * {@link ModelTier} the call belongs to.
 *
 * <p>Attaching options here rather than at the call sites keeps the decision
 * next to the prompt it belongs to: the planning prompt is deterministic and
 * cheap-model work because of what a planning prompt IS, not because of which
 * service happens to send it. {@link ChatOptions} is Spring AI's
 * provider-neutral options type, so this stays free of any OpenAI-specific
 * class, consistent with the no-provider-lock-in rule the rest of the AI layer
 * follows.
 *
 * <h2>How the tiers are assigned, and the rule behind it</h2>
 *
 * The split is not "important calls get the good model". It is whether the
 * output is read by a machine or by a person:
 *
 * <ul>
 *   <li><b>FAST</b> - agent planning (two booleans) and structured support
 *       answers (a schema). Both are parsed by code, both run on every request
 *       that reaches them, and neither gets better with a stronger model - they
 *       get more expensive.</li>
 *   <li><b>CAPABLE</b> - RAG answers, agent synthesis, tool-result
 *       explanations, open-ended chat. These are the text a user reads, and the
 *       only place model capability shows up in the product.</li>
 * </ul>
 *
 * <p>The tool-calling round is CAPABLE deliberately, even though "decide which
 * tool to call" sounds like classification. It is not only deciding: it also
 * reports a customer's order status, payment amount, or shipment state in
 * prose, and a weaker model paraphrasing a financial figure is a correctness
 * problem, not a cost one. Splitting selection from explanation into two calls
 * would let the selection half drop to FAST - a real future optimization, and a
 * second provider round trip to pay for it.
 *
 * <h2>Prompt caching</h2>
 *
 * Every prompt built here puts its stable system message first and the
 * request-specific content last. That ordering is not cosmetic: providers cache
 * on an exact prefix, so a system persona followed by variable content shares a
 * cached prefix across every request using that persona, while interleaving the
 * two shares nothing. It costs nothing to arrange and it is the difference
 * between paying full price for the persona on every request and paying it
 * once. The saving shows up as {@code prompt_cached} tokens in
 * {@link com.example.aiplatform.observability.CostMeter}.
 *
 * <p>The one place this ordering is at risk is the agent workflow, which
 * splices conversation history between the system message and the user message
 * - see {@code AgentServiceImpl.withHistory}. The system prefix is still first
 * and still stable, so the cacheable prefix survives; it simply stops growing
 * past that point once history starts varying.
 */
@Component
public class SupportPromptBuilder implements PromptBuilder {

    private final ChatOptions conversationalOptions;
    private final ChatOptions groundedOptions;
    private final ChatOptions deterministicFastOptions;
    private final ChatOptions judgeOptions;

    private final SystemMessage systemMessage;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate structuredUserPromptTemplate;
    private final SystemMessage ragSystemMessage;
    private final PromptTemplate ragUserPromptTemplate;
    private final SystemMessage toolsSystemMessage;
    private final SystemMessage agentPlanningSystemMessage;
    private final SystemMessage agentFinalSystemMessage;
    private final PromptTemplate agentFinalUserPromptTemplate;
    private final SystemMessage judgeSystemMessage;
    private final PromptTemplate judgeUserPromptTemplate;

    public SupportPromptBuilder(
            @Value("classpath:/prompts/support-system.st") Resource systemPromptResource,
            @Value("classpath:/prompts/support-user.st") Resource userPromptResource,
            @Value("classpath:/prompts/support-user-structured.st") Resource structuredUserPromptResource,
            @Value("classpath:/prompts/rag-system.st") Resource ragSystemPromptResource,
            @Value("classpath:/prompts/rag-user.st") Resource ragUserPromptResource,
            @Value("classpath:/prompts/tools-system.st") Resource toolsSystemPromptResource,
            @Value("classpath:/prompts/agent-planning-system.st") Resource agentPlanningSystemPromptResource,
            @Value("classpath:/prompts/agent-final-system.st") Resource agentFinalSystemPromptResource,
            @Value("classpath:/prompts/agent-final-user.st") Resource agentFinalUserPromptResource,
            @Value("classpath:/prompts/judge-system.st") Resource judgeSystemPromptResource,
            @Value("classpath:/prompts/judge-user.st") Resource judgeUserPromptResource,
            TemperatureProperties temperatureProperties,
            ModelTierProperties modelTierProperties) {
        this.conversationalOptions = options(temperatureProperties.conversational(),
                modelTierProperties.nameFor(ModelTier.CAPABLE));
        this.groundedOptions = options(temperatureProperties.grounded(),
                modelTierProperties.nameFor(ModelTier.CAPABLE));
        this.deterministicFastOptions = options(temperatureProperties.deterministic(),
                modelTierProperties.nameFor(ModelTier.FAST));
        // A judge that is cheaper than what it judges is not a judge. Scoring
        // groundedness is harder than producing the answer being scored, so the
        // evaluation tier deliberately spends the capable model - and runs
        // offline, where that cost is bounded by the dataset size rather than
        // by traffic.
        this.judgeOptions = options(temperatureProperties.deterministic(),
                modelTierProperties.nameFor(ModelTier.CAPABLE));
        this.systemMessage = new SystemMessage(readResource(systemPromptResource));
        this.userPromptTemplate = new PromptTemplate(userPromptResource);
        this.structuredUserPromptTemplate = new PromptTemplate(structuredUserPromptResource);
        this.ragSystemMessage = new SystemMessage(readResource(ragSystemPromptResource));
        this.ragUserPromptTemplate = new PromptTemplate(ragUserPromptResource);
        this.toolsSystemMessage = new SystemMessage(readResource(toolsSystemPromptResource));
        this.agentPlanningSystemMessage = new SystemMessage(readResource(agentPlanningSystemPromptResource));
        this.agentFinalSystemMessage = new SystemMessage(readResource(agentFinalSystemPromptResource));
        this.agentFinalUserPromptTemplate = new PromptTemplate(agentFinalUserPromptResource);
        this.judgeSystemMessage = new SystemMessage(readResource(judgeSystemPromptResource));
        this.judgeUserPromptTemplate = new PromptTemplate(judgeUserPromptResource);
    }

    @Override
    public Prompt buildSupportPrompt(String question) {
        Message userMessage = userPromptTemplate.createMessage(Map.of("question", question));
        // Open-ended prose with no retrieved context to stay faithful to.
        return new Prompt(List.of(systemMessage, userMessage), conversationalOptions);
    }

    @Override
    public Prompt buildStructuredSupportPrompt(String question, String formatInstructions) {
        Message userMessage = structuredUserPromptTemplate.createMessage(
                Map.of("question", question, "format", formatInstructions));
        // Output is parsed against a schema - creative sampling only produces
        // parse failures here, never a better answer, and a frontier model only
        // produces the same JSON at a higher price.
        return new Prompt(List.of(systemMessage, userMessage), deterministicFastOptions);
    }

    @Override
    public Prompt buildToolsSupportPrompt(String question) {
        // Reuses the plain support-user.st template - it's just "Support
        // question: {question}" either way. Only the system persona differs
        // (tools-system.st grounds the model in "use a tool, don't guess").
        Message userMessage = userPromptTemplate.createMessage(Map.of("question", question));
        // Answers report what a tool returned; low temperature keeps the model
        // from embellishing a status or an amount into something friendlier.
        return new Prompt(List.of(toolsSystemMessage, userMessage), groundedOptions);
    }

    @Override
    public Prompt buildRagPrompt(String question, String context) {
        Message userMessage = ragUserPromptTemplate.createMessage(Map.of("question", question, "context", context));
        // Every claim must trace to a retrieved excerpt - the least appropriate
        // place in the application for creative sampling.
        return new Prompt(List.of(ragSystemMessage, userMessage), groundedOptions);
    }

    @Override
    public Prompt buildAgentPlanningPrompt(String question, String formatInstructions) {
        // Reuses the same question+format template as buildStructuredSupportPrompt
        // (support-user-structured.st) - same shape, different system persona.
        Message userMessage = structuredUserPromptTemplate.createMessage(
                Map.of("question", question, "format", formatInstructions));
        // Emits two booleans. The same question should route the same way every
        // time; at 0.7 it demonstrably did not. And routing runs on every agent
        // request, which makes it the highest-value call to move off the
        // expensive model.
        return new Prompt(List.of(agentPlanningSystemMessage, userMessage), deterministicFastOptions);
    }

    @Override
    public Prompt buildAgentFinalPrompt(String question, String evidence) {
        Message userMessage = agentFinalUserPromptTemplate.createMessage(
                Map.of("question", question, "evidence", evidence));
        // Synthesis over gathered evidence - same contract as RAG.
        return new Prompt(List.of(agentFinalSystemMessage, userMessage), groundedOptions);
    }

    @Override
    public Prompt buildJudgePrompt(String question, String answer, String context, String formatInstructions) {
        Message userMessage = judgeUserPromptTemplate.createMessage(
                Map.of("question", question, "answer", answer, "context", context, "format", formatInstructions));
        return new Prompt(List.of(judgeSystemMessage, userMessage), judgeOptions);
    }

    /**
     * Model and temperature together, as one portable {@link ChatOptions}.
     *
     * <p>Naming the model per prompt overrides the provider-level default in
     * {@code spring.ai.openai.chat.options.model} for that call only. That is
     * the whole mechanism behind model tiering, and it is portable: Spring AI
     * carries {@code ChatOptions.model} to whichever provider is configured
     * rather than to an OpenAI-specific field.
     */
    private static ChatOptions options(double temperature, String model) {
        return ChatOptions.builder().temperature(temperature).model(model).build();
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt resource: " + resource.getFilename(), e);
        }
    }
}
