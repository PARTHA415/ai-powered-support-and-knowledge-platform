package com.example.aiplatform.security;

import com.example.aiplatform.model.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Replaces Phase 8-10's {@code CallerContextHolder} placeholder. Where that
 * class was a hand-managed ThreadLocal that application code had to
 * remember to set before an LLM call and clear afterward, this reads
 * directly from Spring Security's SecurityContext - which the framework
 * already populates and clears automatically for every request. Nothing in
 * the agent/tool/assistant services needs to manage caller identity
 * manually anymore; it was only ever a stand-in for exactly this.
 *
 * Tool-level and other authorization checks call these methods, never the
 * LLM - the model has no way to influence what this returns, which is the
 * concrete mechanism behind "the LLM must never determine authorization."
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppUserPrincipal get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new IllegalStateException("No authenticated caller in the security context");
        }
        return principal;
    }

    public static String customerId() {
        return get().getCustomerId();
    }

    public static Role role() {
        return get().getRole();
    }
}
