package com.nivas.auth.domain;

import java.util.Objects;
import java.util.UUID;

import com.nivas.common.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Stored in a {@code citext} column, so uniqueness is case-insensitive at the
     * database level rather than depending on every call site remembering to
     * lower-case.
     *
     * <p>{@code columnDefinition} is required, not decorative: citext reports as
     * JDBC type {@code OTHER}, while Hibernate expects {@code VARCHAR} for a
     * String. Without this, {@code ddl-auto: validate} refuses to start the
     * application at all. Binding still works normally — Postgres casts
     * varchar to citext implicitly.
     */
    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role = Role.SEEKER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    protected User() {
        // for JPA
    }

    public User(String email, String passwordHash, String fullName, String phone, Role role) {
        // Normalised here so it is impossible to persist a non-normalised address,
        // whatever the call site does.
        this.email = normaliseEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.phone = phone;
        this.role = role;
    }

    /**
     * Lower-cases and trims an address.
     *
     * <p>Locale.ROOT is deliberate: the Turkish locale lower-cases 'I' to a
     * dotless 'ı', which would make a user's own address stop matching depending
     * on the server's default locale.
     */
    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    /**
     * Identity is the surrogate key only, and only once assigned. Entities that
     * have not been persisted are equal by reference — the usual JPA contract that
     * keeps a transient entity from colliding with others inside a HashSet.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user) || id == null) {
            return false;
        }
        return id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
