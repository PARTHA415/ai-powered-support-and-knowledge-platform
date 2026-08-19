package com.example.aiplatform.model;

/**
 * One entry in an {@link AgentAuditTrail}: what step ran, whether it
 * succeeded, a human-readable summary (the decision made, or the failure
 * reason), and how long it took. capability is one of "PLANNING",
 * "KNOWLEDGE_BASE", "BUSINESS_TOOL", or "FINALIZE".
 */
public record AgentStepRecord(
        String capability,
        boolean success,
        String detail,
        long durationMillis
) {
}
