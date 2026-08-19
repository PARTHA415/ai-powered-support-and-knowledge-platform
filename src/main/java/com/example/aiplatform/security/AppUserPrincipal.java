package com.example.aiplatform.security;

import com.example.aiplatform.model.AppUser;
import com.example.aiplatform.model.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated principal Spring Security carries for the rest of a
 * request's lifetime once login succeeds. Wrapping {@link AppUser} (rather
 * than using Spring Security's plain {@code User}) is what lets tool-level
 * authorization (SupportTools, via {@link CurrentUser}) read customerId and
 * role straight off the authenticated identity - not from anything the
 * caller submitted in the request body, which is the whole point of this
 * phase's authorization model.
 */
public class AppUserPrincipal implements UserDetails {

    private final AppUser appUser;

    public AppUserPrincipal(AppUser appUser) {
        this.appUser = appUser;
    }

    public String getCustomerId() {
        return appUser.getCustomerId();
    }

    public Role getRole() {
        return appUser.getRole();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name()));
    }

    @Override
    public String getPassword() {
        return appUser.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return appUser.getUsername();
    }
}
