package com.example.aiplatform.ai.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import com.example.aiplatform.config.ModelTierProperties;
import com.example.aiplatform.config.TemperatureProperties;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class SupportPromptBuilderTest {

    private final SupportPromptBuilder promptBuilder = new SupportPromptBuilder(
            new ClassPathResource("prompts/support-system.st"),
            new ClassPathResource("prompts/support-user.st"),
            new ClassPathResource("prompts/support-user-structured.st"),
            new ClassPathResource("prompts/rag-system.st"),
            new ClassPathResource("prompts/rag-user.st"),
            new ClassPathResource("prompts/tools-system.st"),
            new ClassPathResource("prompts/agent-planning-system.st"),
            new ClassPathResource("prompts/agent-final-system.st"),
            new ClassPathResource("prompts/agent-final-user.st"),
            new ClassPathResource("prompts/judge-system.st"),
            new ClassPathResource("prompts/judge-user.st"),
                new TemperatureProperties(0.7, 0.2, 0.0),
                new ModelTierProperties("gpt-4o-mini", "gpt-4o"));

    @Test
    void buildsPromptWithSystemPersonaAndInterpolatedUserQuestion() {
        Prompt prompt = promptBuilder.buildSupportPrompt("How do I reset my password?");

        assertThat(prompt.getInstructions()).hasSize(2);

        Message systemMessage = prompt.getInstructions().get(0);
        assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(systemMessage.getText()).contains("technical support");

        Message userMessage = prompt.getInstructions().get(1);
        assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(userMessage.getText())
                .contains("How do I reset my password?")
                .contains("Support question:");
    }

    @Test
    void buildsStructuredPromptWithQuestionAndFormatInstructions() {
        Prompt prompt = promptBuilder.buildStructuredSupportPrompt(
                "How do I reset my password?", "Respond only with JSON matching this schema: {...}");

        assertThat(prompt.getInstructions()).hasSize(2);

        Message userMessage = prompt.getInstructions().get(1);
        assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(userMessage.getText())
                .contains("How do I reset my password?")
                .contains("Respond only with JSON matching this schema: {...}");
    }

    @Test
    void buildsRagPromptWithGroundedSystemPersonaAndContext() {
        Prompt prompt = promptBuilder.buildRagPrompt(
                "How do I reset my password?", "[1] (Password Reset Guide) Go to Settings > Security.");

        assertThat(prompt.getInstructions()).hasSize(2);

        Message systemMessage = prompt.getInstructions().get(0);
        assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(systemMessage.getText())
                .contains("ONLY")
                .contains("knowledge base");

        Message userMessage = prompt.getInstructions().get(1);
        assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(userMessage.getText())
                .contains("How do I reset my password?")
                .contains("[1] (Password Reset Guide) Go to Settings > Security.");
    }

    @Test
    void buildsToolsSupportPromptWithToolAwareSystemPersona() {
        Prompt prompt = promptBuilder.buildToolsSupportPrompt("What's the status of order ORD-1001?");

        assertThat(prompt.getInstructions()).hasSize(2);

        Message systemMessage = prompt.getInstructions().get(0);
        assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(systemMessage.getText())
                .contains("tools")
                .contains("never guess")
                .contains("invent order numbers");

        Message userMessage = prompt.getInstructions().get(1);
        assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(userMessage.getText()).contains("What's the status of order ORD-1001?");
    }

    @Test
    void buildsAgentPlanningPromptWithQuestionAndFormatInstructions() {
        Prompt prompt = promptBuilder.buildAgentPlanningPrompt(
                "What's the status of order ORD-1001?", "Respond only with JSON matching this schema: {...}");

        assertThat(prompt.getInstructions()).hasSize(2);

        Message systemMessage = prompt.getInstructions().get(0);
        assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(systemMessage.getText())
                .contains("needsKnowledgeBase")
                .contains("needsBusinessTool");

        Message userMessage = prompt.getInstructions().get(1);
        assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(userMessage.getText())
                .contains("What's the status of order ORD-1001?")
                .contains("Respond only with JSON matching this schema: {...}");
    }

    @Test
    void buildsAgentFinalPromptWithQuestionAndEvidence() {
        Prompt prompt = promptBuilder.buildAgentFinalPrompt(
                "What's the status of order ORD-1001?", "Business system lookup result:\nOrder ORD-1001 is SHIPPED.");

        assertThat(prompt.getInstructions()).hasSize(2);

        Message systemMessage = prompt.getInstructions().get(0);
        assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(systemMessage.getText()).contains("ONLY");

        Message userMessage = prompt.getInstructions().get(1);
        assertThat(userMessage.getMessageType()).isEqualTo(MessageType.USER);
        assertThat(userMessage.getText())
                .contains("What's the status of order ORD-1001?")
                .contains("Order ORD-1001 is SHIPPED.");
    }
}
