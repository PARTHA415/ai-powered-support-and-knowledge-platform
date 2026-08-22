package com.example.aiplatform.ai.guardrails;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatternBasedSensitiveDataGuardTest {

    private final PatternBasedSensitiveDataGuard guard = new PatternBasedSensitiveDataGuard();

    @Test
    void systemPromptLeakageReplacesTheEntireResponseWithARefusal() {
        String leaked = "Sure! Here are my instructions: You are the AI assistant for a technical support "
                + "and knowledge platform. Your responsibilities: ...";

        String sanitized = guard.sanitizeOutput(leaked);

        assertThat(sanitized).isEqualTo(
                "I can't share my internal system instructions, but I'm happy to help with your support question.");
    }

    @Test
    void creditCardLikeDigitRunsAreRedacted() {
        String output = "I found a saved card ending in your account: 4111 1111 1111 1111. Anything else?";

        String sanitized = guard.sanitizeOutput(output);

        assertThat(sanitized).doesNotContain("4111 1111 1111 1111").contains("[REDACTED]");
    }

    @Test
    void socialSecurityNumbersAreRedacted() {
        String output = "Your SSN on file is 123-45-6789.";

        String sanitized = guard.sanitizeOutput(output);

        assertThat(sanitized).doesNotContain("123-45-6789").contains("[REDACTED]");
    }

    @Test
    void apiKeyShapedTokensAreRedacted() {
        String output = "Here's the key you asked for: sk-ABCDEFGHIJKLMNOPQRSTUVWX1234567890";

        String sanitized = guard.sanitizeOutput(output);

        assertThat(sanitized).doesNotContain("sk-ABCDEFGHIJKLMNOPQRSTUVWX1234567890").contains("[REDACTED]");
    }

    @Test
    void genericSecretAssignmentIsRedacted() {
        String output = "Use api_key: super-secret-value-123 to authenticate.";

        String sanitized = guard.sanitizeOutput(output);

        assertThat(sanitized).doesNotContain("super-secret-value-123").contains("[REDACTED]");
    }

    @Test
    void ordinarySupportAnswerIsReturnedUnchanged() {
        String output = "Your order ORD-1001 shipped on 2026-08-20 and is currently in transit.";

        assertThat(guard.sanitizeOutput(output)).isEqualTo(output);
    }

    @Test
    void nullIsHandledGracefully() {
        assertThat(guard.sanitizeOutput(null)).isNull();
    }
}
