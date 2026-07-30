package com.nivas.auth.domain;

/**
 * Coarse-grained role. Fine-grained rules ("may this user edit <i>this</i>
 * listing?") are ownership checks in the service layer, not roles — encoding
 * them as authorities would mean reissuing tokens whenever ownership changes.
 */
public enum Role {

    /** Browses and enquires. The default for self-registration. */
    SEEKER,

    /** Lists their own property. */
    OWNER,

    /** Lists on behalf of others; same permissions as OWNER today, separated for reporting. */
    AGENT,

    /** Moderates listings and can act on any record. */
    ADMIN;

    /** Spring Security's {@code hasRole()} expects the {@code ROLE_} prefix. */
    public String authority() {
        return "ROLE_" + name();
    }
}
