package com.example.aiplatform.ai.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class SupportPromptBuilderTest {

    private final SupportPromptBuilder promptBuilder = new SupportPromptBuilder(
            new ClassPathResource("prompts/support-system.st"),
            new ClassPathResource("prompts/support-user.st"),
            new ClassPathResource("prompts/support-user-structured.st"),
            new ClassPathResource("prompts/rag-system.st"),
            new ClassPathResource("prompts/rag-user.st"),
            new ClassPathResource("prompts/tools-system.st"));

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
}
