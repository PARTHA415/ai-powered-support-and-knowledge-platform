package com.example.aiplatform.model;

public record SupportAnswer(
        String answer,
        SupportCategory category,
        ConfidenceLevel confidence,
        boolean needsHumanEscalation
) {
}
