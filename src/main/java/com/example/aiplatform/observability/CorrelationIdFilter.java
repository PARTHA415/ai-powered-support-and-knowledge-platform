package com.example.aiplatform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Opens the {@link RequestContext} for one incoming request and closes it
 * afterwards - the single lifecycle boundary for every piece of per-request
 * state this application keeps outside the request object itself: the
 * correlation ID, and the tool-call counter that
 * {@link com.example.aiplatform.ai.guardrails.ToolExecutionGuard} enforces
 * against.
 *
 * <p>Those were two filters and two thread-locals before. One filter is not
 * merely tidier: it makes it structurally impossible for a request to have a
 * correlation ID but no tool budget, or a tool budget scoped differently from
 * its trace - and it puts the "install, then always remove" discipline that
 * pooled threads demand in exactly one place instead of two.
 *
 * <p>The correlation ID goes into SLF4J's MDC (see the
 * {@code %X{correlationId}} in application.yml's logging pattern), which every
 * log statement automatically includes. That is the mechanism behind "trace one
 * user request across REST -&gt; RAG -&gt; Vector DB -&gt; LLM -&gt; Tool -&gt;
 * Response": grep for one ID and every line from every layer, for that one
 * request, comes back together, in order. Unlike the previous arrangement, that
 * now holds for work the agent runs on a worker thread too, because
 * {@link RequestContextHolder#propagate} carries the MDC entry with the context.
 *
 * <p>If the caller already supplied an {@code X-Correlation-Id} header (e.g. a
 * gateway or another service that started the trace upstream), that value
 * is reused instead of minting a new one - this application becomes one
 * more hop in a wider trace rather than starting a new, disconnected one.
 * The ID is also echoed back on the response so a client can log/display it
 * for later correlation (e.g. in a bug report).
 *
 * <p>Ordered to run first ({@link Ordered#HIGHEST_PRECEDENCE}) so the context
 * exists before Spring Security's own filters - and therefore every other
 * filter and the controller itself - ever log anything or count anything for
 * this request.
 *
 * <p>Deliberately NOT attached as a Micrometer metric tag anywhere: a
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

        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try (RequestContextHolder.Scope ignored =
                     RequestContextHolder.open(RequestContext.forRequest(correlationId))) {
            filterChain.doFilter(request, response);
        }
    }
}
