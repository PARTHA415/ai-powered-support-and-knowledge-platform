package com.example.aiplatform.ai.prompt;

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
 * Builds every prompt in the application, and - as of the sampling-temperature
 * fix - decides the {@link ChatOptions} each one carries.
 *
 * <p>Attaching options here rather than at the call sites keeps the decision
 * next to the prompt it belongs to: the planning prompt is deterministic
 * because of what a planning prompt IS, not because of which service happens
 * to send it. {@link ChatOptions} is Spring AI's provider-neutral options
 * type, so this stays free of any OpenAI-specific class, consistent with the
 * no-provider-lock-in rule the rest of the AI layer follows.
 */
@Component
public class SupportPromptBuilder implements PromptBuilder {

    private final ChatOptions conversationalOptions;
    private final ChatOptions groundedOptions;
    private final ChatOptions deterministicOptions;

    private final SystemMessage systemMessage;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate structuredUserPromptTemplate;
    private final SystemMessage ragSystemMessage;
    private final PromptTemplate ragUserPromptTemplate;
    private final SystemMessage toolsSystemMessage;
    private final SystemMessage agentPlanningSystemMessage;
    private final SystemMessage agentFinalSystemMessage;
    private final PromptTemplate agentFinalUserPromptTemplate;

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
            TemperatureProperties temperatureProperties) {
        this.conversationalOptions = temperature(temperatureProperties.conversational());
        this.groundedOptions = temperature(temperatureProperties.grounded());
        this.deterministicOptions = temperature(temperatureProperties.deterministic());
        this.systemMessage = new SystemMessage(readResource(systemPromptResource));
        this.userPromptTemplate = new PromptTemplate(userPromptResource);
        this.structuredUserPromptTemplate = new PromptTemplate(structuredUserPromptResource);
        this.ragSystemMessage = new SystemMessage(readResource(ragSystemPromptResource));
        this.ragUserPromptTemplate = new PromptTemplate(ragUserPromptResource);
        this.toolsSystemMessage = new SystemMessage(readResource(toolsSystemPromptResource));
        this.agentPlanningSystemMessage = new SystemMessage(readResource(agentPlanningSystemPromptResource));
        this.agentFinalSystemMessage = new SystemMessage(readResource(agentFinalSystemPromptResource));
        this.agentFinalUserPromptTemplate = new PromptTemplate(agentFinalUserPromptResource);
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
        // parse failures here, never a better answer.
        return new Prompt(List.of(systemMessage, userMessage), deterministicOptions);
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
        // time; at 0.7 it demonstrably did not.
        return new Prompt(List.of(agentPlanningSystemMessage, userMessage), deterministicOptions);
    }

    @Override
    public Prompt buildAgentFinalPrompt(String question, String evidence) {
        Message userMessage = agentFinalUserPromptTemplate.createMessage(
                Map.of("question", question, "evidence", evidence));
        // Synthesis over gathered evidence - same contract as RAG.
        return new Prompt(List.of(agentFinalSystemMessage, userMessage), groundedOptions);
    }

    private static ChatOptions temperature(double value) {
        return ChatOptions.builder().temperature(value).build();
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt resource: " + resource.getFilename(), e);
        }
    }
}
