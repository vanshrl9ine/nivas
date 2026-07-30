package com.nivas.savedsearch.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.nivas.auth.domain.User;
import com.nivas.common.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A stored filter set, optionally alerting when new listings match.
 *
 * <p>The criteria are kept as the serialised search request rather than as
 * columns. Two reasons: replaying an alert then runs the <em>exact</em> query the
 * user built, and adding a new filter to search does not require a migration here.
 * The cost is that the criteria are opaque to SQL — acceptable, since nothing
 * queries across them. It is {@code jsonb} rather than {@code json} so that
 * becomes possible later without a rewrite.
 */
@Entity
@Table(name = "saved_searches")
public class SavedSearch extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria", nullable = false, columnDefinition = "jsonb")
    private String criteria;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_frequency", nullable = false, length = 16)
    private AlertFrequency alertFrequency = AlertFrequency.NONE;

    @Column(name = "last_alerted_at")
    private Instant lastAlertedAt;

    protected SavedSearch() {
        // for JPA
    }

    public SavedSearch(User user, String name, String criteria, AlertFrequency alertFrequency) {
        this.user = user;
        this.name = name;
        this.criteria = criteria;
        this.alertFrequency = alertFrequency == null ? AlertFrequency.NONE : alertFrequency;
    }

    public void update(String name, String criteria, AlertFrequency alertFrequency) {
        this.name = name;
        this.criteria = criteria;
        this.alertFrequency = alertFrequency == null ? AlertFrequency.NONE : alertFrequency;
    }

    /**
     * The point in time an alert should look forward from.
     *
     * <p>A search that has never alerted uses its creation time, so subscribing
     * does not immediately dump the entire back catalogue into someone's inbox.
     */
    public Instant alertWatermark() {
        return lastAlertedAt != null ? lastAlertedAt : getCreatedAt();
    }

    public boolean isDueAt(Instant now) {
        if (!alertFrequency.alerts()) {
            return false;
        }
        if (lastAlertedAt == null) {
            return true;
        }
        return lastAlertedAt.plus(alertFrequency.minimumInterval()).isBefore(now);
    }

    /**
     * Records that the owner has been told about everything up to {@code upTo}.
     *
     * <p>Takes the instant the search <em>ran</em>, not "now". Using now would
     * skip any listing published while the job was working.
     */
    public void markAlerted(Instant upTo) {
        this.lastAlertedAt = upTo;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getName() {
        return name;
    }

    public String getCriteria() {
        return criteria;
    }

    public AlertFrequency getAlertFrequency() {
        return alertFrequency;
    }

    public Instant getLastAlertedAt() {
        return lastAlertedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SavedSearch saved) || id == null) {
            return false;
        }
        return id.equals(saved.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
