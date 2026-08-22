package com.example.aiplatform.ai.guardrails;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resets {@link ToolExecutionGuard}'s per-request counter before each
 * request and clears it afterward, the same lifecycle Spring Security
 * manages for its own SecurityContext ThreadLocal - necessary because
 * servlet containers reuse threads across requests, so a stale count from a
 * previous request would otherwise leak into the next one on the same
 * thread. Registered automatically by Spring Boot because it's a
 * {@code Filter} bean; no explicit FilterRegistrationBean needed.
 */
@Component
public class GuardrailRequestFilter extends OncePerRequestFilter {

    private final ToolExecutionGuard toolExecutionGuard;

    public GuardrailRequestFilter(ToolExecutionGuard toolExecutionGuard) {
        this.toolExecutionGuard = toolExecutionGuard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        toolExecutionGuard.reset();
        try {
            filterChain.doFilter(request, response);
        } finally {
            toolExecutionGuard.clear();
        }
    }
}
