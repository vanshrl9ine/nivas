package com.nivas.billing;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nivas.billing.domain.PaymentEvent;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    /**
     * The idempotency check for webhooks.
     *
     * <p>Backed by a unique constraint, so this is an optimisation rather than
     * the guarantee — a concurrent redelivery is caught by the database.
     */
    Optional<PaymentEvent> findByGatewayAndEventId(String gateway, String eventId);

    boolean existsByGatewayAndEventId(String gateway, String eventId);
}
