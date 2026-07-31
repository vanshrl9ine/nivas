package com.nivas.billing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.billing.domain.OrderStatus;
import com.nivas.billing.domain.PaymentOrder;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    /** The lookup a webhook does: the gateway knows its own id, not ours. */
    Optional<PaymentOrder> findByGatewayAndGatewayOrderId(String gateway, String gatewayOrderId);

    Page<PaymentOrder> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<PaymentOrder> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Orders abandoned at the payment step.
     *
     * <p>Worth sweeping because each one may be holding a slot the seller could
     * otherwise sell.
     */
    @Query("SELECT o FROM PaymentOrder o WHERE o.status = com.nivas.billing.domain.OrderStatus.CREATED "
            + "AND o.createdAt < :before")
    List<PaymentOrder> findStale(@Param("before") Instant before);

    long countByUserIdAndStatus(UUID userId, OrderStatus status);
}
