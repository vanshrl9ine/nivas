package com.nivas.catalog.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Listing lifecycle.
 *
 * <p>Transitions are enumerated here rather than scattered through the service
 * so the legal state machine is readable in one place — and so an illegal jump
 * (say ARCHIVED back to ACTIVE) fails loudly instead of quietly corrupting the
 * search index.
 */
public enum PropertyStatus {

    /** Owner is still editing. Never indexed, never publicly visible. */
    DRAFT,

    /** Submitted, awaiting moderation. */
    PENDING_REVIEW,

    /** Live and searchable. The only status present in the search index. */
    ACTIVE,

    SOLD,
    RENTED,
    ARCHIVED;

    private static final Set<PropertyStatus> SEARCHABLE = EnumSet.of(ACTIVE);

    public boolean isSearchable() {
        return SEARCHABLE.contains(this);
    }

    /** Terminal-ish states an owner can no longer edit back into circulation. */
    public boolean isClosed() {
        return this == SOLD || this == RENTED || this == ARCHIVED;
    }

    public boolean canTransitionTo(PropertyStatus target) {
        if (this == target) {
            return true;
        }
        return switch (this) {
            case DRAFT -> target == PENDING_REVIEW || target == ARCHIVED;
            case PENDING_REVIEW -> target == ACTIVE || target == DRAFT || target == ARCHIVED;
            case ACTIVE -> target == SOLD || target == RENTED || target == ARCHIVED || target == DRAFT;
            case SOLD, RENTED -> target == ARCHIVED;
            case ARCHIVED -> false;
        };
    }
}
