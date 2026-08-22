package com.example.aiplatform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns one correlation ID per incoming request and puts it in SLF4J's
 * MDC (Mapped Diagnostic Context) - a thread-local map every log statement
 * automatically includes (see the {@code %X{correlationId}} in
 * application.yml's logging pattern). Because this application's entire
 * pipeline - controller -&gt; RAG -&gt; vector-DB repository -&gt; LLM client -&gt;
 * tool -&gt; response - runs synchronously on the ONE thread handling the
 * request (no async hand-off anywhere in this codebase), setting the MDC
 * value once here makes it appear in every log line any of those layers
 * produce for this request, with no need to thread an ID through method
 * signatures by hand. This is the mechanism behind "trace one user request
 * across REST -&gt; RAG -&gt; Vector DB -&gt; LLM -&gt; Tool -&gt; Response": grep the
 * logs for one correlation ID and every line from every layer, for that one
 * request, comes back together, in order.
 *
 * If the caller already supplied an {@code X-Correlation-Id} header (e.g. a
 * gateway or another service that started the trace upstream), that value
 * is reused instead of minting a new one - this application becomes one
 * more hop in a wider trace rather than starting a new, disconnected one.
 * The ID is also echoed back on the response so a client can log/display it
 * for later correlation (e.g. in a bug report).
 *
 * Ordered to run first ({@link Ordered#HIGHEST_PRECEDENCE}) so the
 * correlation ID is present in MDC before Spring Security's own filters -
 * and therefore every other filter and the controller itself - ever log
 * anything for this request.
 *
 * Deliberately NOT attached as a Micrometer metric tag anywhere: a
 * correlation ID is unique per request by design, and Prometheus tags are
 * meant to be low-cardinality (a handful of distinct values, aggregated
 * across many requests) - attaching a value that's different on every
 * single request would create a new time series per request and can crash
 * or overwhelm a Prometheus server ("cardinality explosion"). Correlation
 * IDs belong in logs (and, in a system with real distributed tracing, in
 * trace/span IDs) - never in a metric label.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
