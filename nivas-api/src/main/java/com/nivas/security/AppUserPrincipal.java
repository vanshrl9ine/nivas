package com.nivas.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;

/**
 * The authenticated caller, as the rest of the application sees them.
 *
 * <p>Holds the user id so ownership checks never need a database round trip just
 * to turn an email into an id. Deliberately does not hold the {@link User}
 * entity: that would drag a detached JPA object into the security context and
 * into every request thread.
 */
public class AppUserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    public AppUserPrincipal(UUID id, String email, String passwordHash, Role role, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static AppUserPrincipal from(User user) {
        return new AppUserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getRole(), user.isEnabled());
    }

    /**
     * Rebuilds a principal from JWT claims, with no password hash — nothing
     * downstream of authentication needs it, and not loading it keeps the
     * credential out of memory on every authenticated request.
     */
    public static AppUserPrincipal fromClaims(UUID id, String email, Role role) {
        return new AppUserPrincipal(id, email, null, role, true);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
