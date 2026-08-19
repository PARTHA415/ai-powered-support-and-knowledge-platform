package com.example.aiplatform.model;

public record AgentResponse(
        String answer,
        String model,
        AgentAuditTrail auditTrail
) {
}
