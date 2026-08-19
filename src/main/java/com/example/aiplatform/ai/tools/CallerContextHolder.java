package com.example.aiplatform.ai.tools;

/**
 * Placeholder for "who is actually asking" until Phase 11 wires up real
 * Spring Security. Tools must never trust an identity the LLM could supply
 * as a plain argument - a malicious or simply confused prompt could ask a
 * tool for someone else's order just by naming a different ID, and the model
 * would have no way to know that's wrong. The caller's identity has to come
 * from outside the LLM's control entirely: request-handling code sets it
 * here before the tool-calling LLM call is made, and tools read it back to
 * authorize access - the same shape {@code SecurityContextHolder} occupies
 * in real Spring Security, deliberately mirrored so swapping this out later
 * is a small change, not a redesign.
 *
 * ThreadLocal-scoped because a single web request is handled on one thread
 * end-to-end here; must be cleared after each request (see
 * SupportAssistantServiceImpl) to avoid leaking one caller's identity into
 * a pooled thread's next, unrelated request.
 */
public final class CallerContextHolder {

    private static final ThreadLocal<String> CURRENT_CUSTOMER_ID = new ThreadLocal<>();

    private CallerContextHolder() {
    }

    public static void setCurrentCustomerId(String customerId) {
        CURRENT_CUSTOMER_ID.set(customerId);
    }

    public static String getCurrentCustomerId() {
        String customerId = CURRENT_CUSTOMER_ID.get();
        if (customerId == null) {
            throw new IllegalStateException("No caller context is set - tools cannot authorize access");
        }
        return customerId;
    }

    public static void clear() {
        CURRENT_CUSTOMER_ID.remove();
    }
}
