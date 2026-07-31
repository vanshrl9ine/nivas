package com.nivas.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.billing.domain.Subscription;
import com.nivas.billing.domain.SubscriptionStatus;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    List<Subscription> findByUserIdOrderByStartedAtDesc(UUID userId);

    /**
     * Retires subscriptions whose period has ended.
     *
     * <p>Matters more than it looks: the partial unique index only permits one
     * ACTIVE row per user, so a stale ACTIVE row would block the user from ever
     * buying again.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Subscription s SET s.status = com.nivas.billing.domain.SubscriptionStatus.EXPIRED
            WHERE s.status = com.nivas.billing.domain.SubscriptionStatus.ACTIVE
              AND s.expiresAt IS NOT NULL AND s.expiresAt < :now
            """)
    int expireLapsed(@Param("now") Instant now);
}
