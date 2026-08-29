package com.example.aiplatform.observability;

import org.slf4j.MDC;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Installs a {@link RequestContext} for the current thread, and moves it to
 * another thread when work does.
 *
 * <p>Modelled on Spring Security's {@code SecurityContextHolder} deliberately,
 * including the part where the holder is static: the alternative is passing a
 * context object through every method between a servlet filter and a
 * {@code @Tool}-annotated business method, which is a large amount of plumbing
 * that exists only to serve cross-cutting concerns and that every new method
 * has to remember to participate in.
 *
 * <p>What it adds over a bare {@code ThreadLocal} is the two operations that
 * make concurrency safe rather than merely possible:
 *
 * <ul>
 *   <li>{@link #open(RequestContext)} returns an {@link AutoCloseable} scope, so
 *       installing without removing is a compile-time-visible mistake rather
 *       than a leak into the next request served by a pooled thread;</li>
 *   <li>{@link #propagate(Callable)} captures the calling thread's context and
 *       installs it around the task on whichever thread runs it - so the
 *       tool-call counter, the correlation ID and the deadline are the same
 *       ones, not fresh copies.</li>
 * </ul>
 *
 * <p>The MDC entry is set and cleared alongside the context, so log lines from a
 * worker thread carry the same correlation ID as the request that spawned them.
 * That is the difference between one greppable story per request and a set of
 * orphaned lines from a pool thread.
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    /** A scope that removes whatever it installed, restoring any previous context. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static Scope open(RequestContext context) {
        RequestContext previous = CURRENT.get();
        String previousCorrelationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        CURRENT.set(context);
        MDC.put(CorrelationIdFilter.MDC_KEY, context.correlationId());
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            if (previousCorrelationId == null) {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            } else {
                MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelationId);
            }
        };
    }

    /**
     * The context for the work in progress.
     *
     * @throws IllegalStateException when there is none. Deliberately loud: the
     *         failure this replaces was a per-request guardrail silently
     *         restarting its count on a thread nobody had told about the
     *         request. An exception naming the problem is recoverable; a
     *         guardrail that quietly stops counting is not detectable at all.
     */
    public static RequestContext current() {
        RequestContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException(
                    "No RequestContext on this thread. Work that runs off the request thread must be wrapped "
                            + "with RequestContextHolder.propagate(...) so per-request guardrails keep counting "
                            + "against the request they belong to.");
        }
        return context;
    }

    public static Optional<RequestContext> currentOrEmpty() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Wraps a task so it runs under the CALLING thread's context, wherever it is executed. */
    public static <T> Callable<T> propagate(Callable<T> task) {
        RequestContext captured = CURRENT.get();
        return () -> {
            if (captured == null) {
                return task.call();
            }
            try (Scope ignored = open(captured)) {
                return task.call();
            }
        };
    }

    /** {@link Supplier} flavour, for the many call sites that cannot throw a checked exception. */
    public static <T> Supplier<T> propagate(Supplier<T> task) {
        RequestContext captured = CURRENT.get();
        return () -> {
            if (captured == null) {
                return task.get();
            }
            try (Scope ignored = open(captured)) {
                return task.get();
            }
        };
    }
}
