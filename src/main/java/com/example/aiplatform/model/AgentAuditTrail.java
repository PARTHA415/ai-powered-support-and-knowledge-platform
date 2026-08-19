package com.example.aiplatform.model;

import java.util.List;

/**
 * The complete, inspectable record of what an agent request actually did -
 * returned to the API caller (transparency into agent behavior is itself a
 * production concern, not just a debugging nicety) and logged via SLF4J for
 * real observability. maxIterationsExceeded and timedOut being true means
 * the workflow stopped early and answered with partial evidence rather than
 * looping further - a deliberate safety exit, not a crash.
 */
public record AgentAuditTrail(
        String requestId,
        String question,
        boolean knowledgeBasePlanned,
        boolean businessToolPlanned,
        List<AgentStepRecord> steps,
        boolean maxIterationsExceeded,
        boolean timedOut,
        long totalDurationMillis
) {
}
