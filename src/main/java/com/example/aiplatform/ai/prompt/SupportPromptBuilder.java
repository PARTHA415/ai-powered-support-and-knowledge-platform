package com.example.aiplatform.ai.prompt;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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

@Component
public class SupportPromptBuilder implements PromptBuilder {

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
            @Value("classpath:/prompts/agent-final-user.st") Resource agentFinalUserPromptResource) {
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
        return new Prompt(List.of(systemMessage, userMessage));
    }

    @Override
    public Prompt buildStructuredSupportPrompt(String question, String formatInstructions) {
        Message userMessage = structuredUserPromptTemplate.createMessage(
                Map.of("question", question, "format", formatInstructions));
        return new Prompt(List.of(systemMessage, userMessage));
    }

    @Override
    public Prompt buildToolsSupportPrompt(String question) {
        // Reuses the plain support-user.st template - it's just "Support
        // question: {question}" either way. Only the system persona differs
        // (tools-system.st grounds the model in "use a tool, don't guess").
        Message userMessage = userPromptTemplate.createMessage(Map.of("question", question));
        return new Prompt(List.of(toolsSystemMessage, userMessage));
    }

    @Override
    public Prompt buildRagPrompt(String question, String context) {
        Message userMessage = ragUserPromptTemplate.createMessage(Map.of("question", question, "context", context));
        return new Prompt(List.of(ragSystemMessage, userMessage));
    }

    @Override
    public Prompt buildAgentPlanningPrompt(String question, String formatInstructions) {
        // Reuses the same question+format template as buildStructuredSupportPrompt
        // (support-user-structured.st) - same shape, different system persona.
        Message userMessage = structuredUserPromptTemplate.createMessage(
                Map.of("question", question, "format", formatInstructions));
        return new Prompt(List.of(agentPlanningSystemMessage, userMessage));
    }

    @Override
    public Prompt buildAgentFinalPrompt(String question, String evidence) {
        Message userMessage = agentFinalUserPromptTemplate.createMessage(
                Map.of("question", question, "evidence", evidence));
        return new Prompt(List.of(agentFinalSystemMessage, userMessage));
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt resource: " + resource.getFilename(), e);
        }
    }
}
