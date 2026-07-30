package com.nivas.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PropertyStatusTest {

    @Test
    @DisplayName("only ACTIVE listings are searchable")
    void onlyActiveIsSearchable() {
        assertThat(PropertyStatus.ACTIVE.isSearchable()).isTrue();

        EnumSet.complementOf(EnumSet.of(PropertyStatus.ACTIVE))
                .forEach(status -> assertThat(status.isSearchable())
                        .as("%s must not be searchable", status)
                        .isFalse());
    }

    @Test
    @DisplayName("ARCHIVED is terminal")
    void archivedIsTerminal() {
        EnumSet.allOf(PropertyStatus.class).stream()
                .filter(target -> target != PropertyStatus.ARCHIVED)
                .forEach(target -> assertThat(PropertyStatus.ARCHIVED.canTransitionTo(target))
                        .as("ARCHIVED must not reopen as %s", target)
                        .isFalse());
    }

    @Test
    @DisplayName("a sold listing cannot go back on the market")
    void soldCannotBeRelisted() {
        assertThat(PropertyStatus.SOLD.canTransitionTo(PropertyStatus.ACTIVE)).isFalse();
        assertThat(PropertyStatus.SOLD.canTransitionTo(PropertyStatus.DRAFT)).isFalse();
        assertThat(PropertyStatus.SOLD.canTransitionTo(PropertyStatus.ARCHIVED)).isTrue();
    }

    @Test
    @DisplayName("a draft cannot skip review straight to live")
    void draftCannotSkipReview() {
        assertThat(PropertyStatus.DRAFT.canTransitionTo(PropertyStatus.ACTIVE)).isFalse();
        assertThat(PropertyStatus.DRAFT.canTransitionTo(PropertyStatus.PENDING_REVIEW)).isTrue();
    }

    @Test
    @DisplayName("an active listing can be taken back to draft for edits")
    void activeCanReturnToDraft() {
        assertThat(PropertyStatus.ACTIVE.canTransitionTo(PropertyStatus.DRAFT)).isTrue();
        assertThat(PropertyStatus.ACTIVE.canTransitionTo(PropertyStatus.SOLD)).isTrue();
        assertThat(PropertyStatus.ACTIVE.canTransitionTo(PropertyStatus.RENTED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(PropertyStatus.class)
    @DisplayName("every status permits a no-op transition to itself")
    void selfTransitionIsAlwaysAllowed(PropertyStatus status) {
        assertThat(status.canTransitionTo(status)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PropertyStatus.class, names = { "SOLD", "RENTED", "ARCHIVED" })
    @DisplayName("closed statuses report themselves as closed")
    void closedStatuses(PropertyStatus status) {
        assertThat(status.isClosed()).isTrue();
    }
}
