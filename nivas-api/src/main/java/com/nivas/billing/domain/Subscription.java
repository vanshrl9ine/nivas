package com.nivas.billing.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
 * The plan a user is currently on.
 *
 * <p>A partial unique index guarantees at most one ACTIVE row per user. That is
 * in the database rather than in this class because two payments landing at the
 * same instant would otherwise each read "no active subscription" and each
 * insert one.
 */
@Entity
@Table(name = "subscriptions")
public class Subscription extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "package_id", nullable = false)
    private SalesPackage salesPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    /** Null means it does not expire, which in practice only the free plan uses. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "order_id")
    private UUID orderId;

    protected Subscription() {
        // for JPA
    }

    public Subscription(User user, SalesPackage salesPackage, UUID orderId) {
        this.user = user;
        this.salesPackage = salesPackage;
        this.orderId = orderId;
        this.startedAt = Instant.now();
        this.expiresAt = this.startedAt.plus(salesPackage.duration());
    }

    public boolean isActiveAt(Instant when) {
        return status == SubscriptionStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(when));
    }

    public void expire() {
        this.status = SubscriptionStatus.EXPIRED;
    }

    public void cancel() {
        this.status = SubscriptionStatus.CANCELLED;
    }

    public int listingQuota() {
        Integer quota = salesPackage.getListingQuota();
        return quota == null ? 0 : quota;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public SalesPackage getSalesPackage() {
        return salesPackage;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Subscription subscription) || id == null) {
            return false;
        }
        return id.equals(subscription.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
