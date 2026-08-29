package com.example.aiplatform.observability;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The per-request state that used to live in three separate thread-locals, as
 * one object that can be handed to another thread.
 *
 * <h2>What was wrong with the thread-locals</h2>
 *
 * Nothing, as long as one request meant one thread for its whole life - which
 * was true of this application and is stated as such in the original
 * {@code CorrelationIdFilter} javadoc. The moment any work moves to a second
 * thread, three things break at once and only one of them is noisy:
 *
 * <ul>
 *   <li>the correlation ID vanishes from the worker thread's log lines, so the
 *       parallel half of a request becomes untraceable - annoying, and
 *       obvious;</li>
 *   <li>the security context vanishes, so a tool authorization check throws
 *       "no authenticated caller" - alarming, and obvious;</li>
 *   <li>the tool-call counter resets to zero on the new thread, so the guardrail
 *       capping tool invocations per request silently permits N times its limit.
 *       Nothing throws. Nothing logs. The guardrail simply stops being a
 *       guardrail.</li>
 * </ul>
 *
 * <p>The third is why this class exists and why it was a blocker for
 * parallelising anything. A safety bound that fails silently under concurrency
 * is worse than no bound, because it is still on the architecture diagram.
 *
 * <h2>Why this is still installed in a ThreadLocal</h2>
 *
 * Because the servlet API offers nowhere else to hang it, and threading a
 * context parameter through every method between the filter and a {@code @Tool}
 * annotated method would be a worse design - Spring Security's
 * {@code SecurityContextHolder} makes the same trade for the same reason. The
 * difference from what this replaces is not the storage, it is that the
 * transfer is now explicit: work that moves to another thread is wrapped by
 * {@link RequestContextHolder#propagate}, and a thread that has no context
 * fails loudly rather than quietly starting a fresh count.
 *
 * <h2>The deadline</h2>
 *
 * A single wall-clock instant for the whole request, rather than a per-step
 * timeout. Per-step timeouts do not compose: three steps at 30 seconds each is
 * a 90-second request, and every individual step is within its bound the whole
 * time. One deadline shared by every step is the only formulation where "this
 * request takes at most N seconds" is a statement that can be true.
 */
public final class RequestContext {

    /** No deadline - the value used for requests with no time bound of their own. */
    private static final long NO_DEADLINE = Long.MAX_VALUE;

    private final String correlationId;
    private final AtomicInteger toolCallCount;
    private final long deadlineNanos;

    private RequestContext(String correlationId, AtomicInteger toolCallCount, long deadlineNanos) {
        this.correlationId = correlationId;
        this.toolCallCount = toolCallCount;
        this.deadlineNanos = deadlineNanos;
    }

    public static RequestContext forRequest(String correlationId) {
        return new RequestContext(correlationId, new AtomicInteger(), NO_DEADLINE);
    }

    /**
     * A view of this context with a deadline attached, sharing the SAME
     * tool-call counter.
     *
     * <p>Sharing rather than copying is the whole point: the agent workflow
     * narrows the time bound for its own portion of the request, and if that
     * produced an independent counter the tool budget would reset exactly where
     * the most tool calls are about to happen.
     */
    public RequestContext withDeadlineIn(long millis) {
        return new RequestContext(correlationId, toolCallCount, System.nanoTime() + millis * 1_000_000L);
    }

    public String correlationId() {
        return correlationId;
    }

    /** Records one tool invocation against this request and returns the new total. */
    public int recordToolCall() {
        return toolCallCount.incrementAndGet();
    }

    public int toolCallCount() {
        return toolCallCount.get();
    }

    public boolean hasDeadline() {
        return deadlineNanos != NO_DEADLINE;
    }

    public boolean isExpired() {
        return hasDeadline() && System.nanoTime() >= deadlineNanos;
    }

    /** Milliseconds left before the deadline; 0 once it has passed, {@link Long#MAX_VALUE} when there is none. */
    public long remainingMillis() {
        if (!hasDeadline()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }
}
