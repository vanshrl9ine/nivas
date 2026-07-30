package com.nivas.savedsearch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;

/**
 * The watermark logic, which is what stands between this feature and being
 * reported as spam.
 */
class SavedSearchTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static SavedSearch savedSearch(AlertFrequency frequency) {
        User user = new User("seeker@nivas.test", "hash", "Seeker", null, Role.SEEKER);
        SavedSearch saved = new SavedSearch(user, "2 BHK Koramangala", "{}", frequency);
        // createdAt is normally set by JPA auditing on persist.
        ReflectionTestUtils.setField(saved, "createdAt", CREATED, Instant.class);
        return saved;
    }

    @Test
    @DisplayName("a search that has never alerted watermarks from its creation time")
    void neverAlertedUsesCreationTime() {
        assertThat(savedSearch(AlertFrequency.DAILY).alertWatermark())
                .as("subscribing must not dump the whole back catalogue into someone's inbox")
                .isEqualTo(CREATED);
    }

    @Test
    @DisplayName("once alerted, the watermark is the last alert time")
    void alertedUsesLastAlertTime() {
        SavedSearch saved = savedSearch(AlertFrequency.DAILY);
        Instant alertedAt = CREATED.plus(Duration.ofDays(3));

        saved.markAlerted(alertedAt);

        assertThat(saved.alertWatermark()).isEqualTo(alertedAt);
        assertThat(saved.getLastAlertedAt()).isEqualTo(alertedAt);
    }

    @Test
    @DisplayName("a NONE search is never due")
    void noneNeverAlerts() {
        SavedSearch saved = savedSearch(AlertFrequency.NONE);

        assertThat(saved.getAlertFrequency().alerts()).isFalse();
        assertThat(saved.isDueAt(CREATED.plus(Duration.ofDays(365)))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AlertFrequency.class, names = { "INSTANT", "DAILY", "WEEKLY" })
    @DisplayName("an alerting search that has never fired is immediately due")
    void firstRunIsDue(AlertFrequency frequency) {
        assertThat(savedSearch(frequency).isDueAt(CREATED)).isTrue();
    }

    @Test
    @DisplayName("a daily search is not due again within the day")
    void dailyRespectsItsInterval() {
        SavedSearch saved = savedSearch(AlertFrequency.DAILY);
        saved.markAlerted(CREATED);

        assertThat(saved.isDueAt(CREATED.plus(Duration.ofHours(23)))).isFalse();
        assertThat(saved.isDueAt(CREATED.plus(Duration.ofHours(25)))).isTrue();
    }

    @Test
    @DisplayName("INSTANT is still throttled to once an hour")
    void instantIsThrottled() {
        SavedSearch saved = savedSearch(AlertFrequency.INSTANT);
        saved.markAlerted(CREATED);

        assertThat(saved.isDueAt(CREATED.plus(Duration.ofMinutes(30))))
                .as("a genuinely per-match notification is how people unsubscribe")
                .isFalse();
        assertThat(saved.isDueAt(CREATED.plus(Duration.ofMinutes(61)))).isTrue();
    }

    @Test
    @DisplayName("a weekly search waits a week")
    void weeklyRespectsItsInterval() {
        SavedSearch saved = savedSearch(AlertFrequency.WEEKLY);
        saved.markAlerted(CREATED);

        assertThat(saved.isDueAt(CREATED.plus(Duration.ofDays(6)))).isFalse();
        assertThat(saved.isDueAt(CREATED.plus(Duration.ofDays(8)))).isTrue();
    }

    @Test
    @DisplayName("the watermark only moves forward, so listings are never re-sent")
    void watermarkAdvancesMonotonically() {
        SavedSearch saved = savedSearch(AlertFrequency.DAILY);

        Instant first = CREATED.plus(Duration.ofDays(1));
        saved.markAlerted(first);
        Instant second = CREATED.plus(Duration.ofDays(2));
        saved.markAlerted(second);

        assertThat(saved.alertWatermark()).isEqualTo(second).isAfter(first);
    }

    @Test
    @DisplayName("a null frequency degrades to NONE rather than throwing")
    void nullFrequencyBecomesNone() {
        User user = new User("seeker@nivas.test", "hash", "Seeker", null, Role.SEEKER);

        assertThat(new SavedSearch(user, "Untitled", "{}", null).getAlertFrequency())
                .isEqualTo(AlertFrequency.NONE);
    }
}
