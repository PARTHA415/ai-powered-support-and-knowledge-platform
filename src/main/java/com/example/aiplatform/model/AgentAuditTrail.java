package com.example.aiplatform.model;

import java.util.List;

/**
 * The complete, inspectable record of what an agent request actually did -
 * returned to the API caller (transparency into agent behavior is itself a
 * production concern, not just a debugging nicety) and logged via SLF4J for
 * real observability.
 *
 * <p>{@code timedOut} being true means the workflow stopped a step early
 * against the request deadline and answered with partial evidence rather than
 * waiting - a deliberate safety exit, not a crash, and the steps list names
 * which capability was cut.
 *
 * <p>There used to be a {@code maxIterationsExceeded} flag beside it. It is
 * gone with the bound it reported: an iteration cap of 5 over a list that could
 * hold 2 could never fire, so the field was permanently false and told a reader
 * of the audit trail that a safety bound existed and had not been reached, when
 * in fact it could not be.
 */
public record AgentAuditTrail(
        String requestId,
        String question,
        boolean knowledgeBasePlanned,
        boolean businessToolPlanned,
        List<AgentStepRecord> steps,
        boolean timedOut,
        long totalDurationMillis
) {
}
