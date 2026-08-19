package com.example.aiplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Safety bounds for the agent workflow (Phase 9). maxIterations caps how
 * many capability steps (knowledge-base search, business tool round) a
 * single request may execute - the concrete defense against a runaway
 * agent loop. timeoutSeconds is a cooperative wall-clock budget for the
 * whole request, checked between steps rather than as a hard preemptive
 * cutoff mid-call (see the Phase 9 docs for why that distinction matters).
 */
@ConfigurationProperties(prefix = "app.agent")
public record AgentProperties(
        @DefaultValue("5") int maxIterations,
        @DefaultValue("30") double timeoutSeconds
) {
}
