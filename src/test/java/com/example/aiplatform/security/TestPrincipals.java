package com.example.aiplatform.security;

import com.example.aiplatform.model.AppUser;
import com.example.aiplatform.model.Role;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Test-only helper for populating Spring Security's SecurityContext with a
 * fake authenticated principal, without needing a real login flow or a
 * database-backed UserDetailsService lookup. Used by any test exercising
 * code that reads {@link CurrentUser} directly (SupportTools, service-layer
 * unit tests) rather than going through MockMvc's own authentication
 * support.
 */
public final class TestPrincipals {

    private TestPrincipals() {
    }

    public static AppUserPrincipal customer(String customerId) {
        return new AppUserPrincipal(new AppUser("test-customer", "unused", customerId, Role.USER));
    }

    public static AppUserPrincipal staff(Role role) {
        return new AppUserPrincipal(new AppUser("test-staff", "unused", null, role));
    }

    public static void authenticateAs(AppUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
