package com.nivas.billing.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.nivas.auth.domain.User;
import com.nivas.common.AuditableEntity;
import com.nivas.common.exception.BadRequestException;

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
 * One attempt to buy a package.
 *
 * <p>Named {@code PaymentOrder} rather than {@code Order} because {@code ORDER}
 * is a SQL keyword and the shadowing gets tedious.
 *
 * <p>The amount is copied from the package at creation rather than read through
 * a relation. A price change must never retroactively alter what somebody was
 * charged, and a dispute is settled by what this row says.
 */
@Entity
@Table(name = "orders")
public class PaymentOrder extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "package_id", nullable = false)
    private SalesPackage salesPackage;

    /** The listing being promoted. Null when buying listing quota. */
    @Column(name = "property_id")
    private UUID propertyId;

    /** Which city a placement was bought for. Null for national surfaces. */
    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(name = "gateway", nullable = false, length = 24)
    private String gateway;

    @Column(name = "gateway_order_id", length = 120)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 120)
    private String gatewayPaymentId;

    @Column(name = "failure_reason", length = 400)
    private String failureReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected PaymentOrder() {
        // for JPA
    }

    public PaymentOrder(User user, SalesPackage salesPackage, UUID propertyId, String city, String gateway) {
        this.user = user;
        this.salesPackage = salesPackage;
        this.propertyId = propertyId;
        this.city = city;
        this.gateway = gateway;
        // Snapshotted deliberately — see the class javadoc.
        this.amountPaise = salesPackage.getPricePaise();
        this.currency = salesPackage.getCurrency();
    }

    public void attachGatewayOrder(String gatewayOrderId) {
        this.gatewayOrderId = gatewayOrderId;
    }

    /**
     * Records a confirmed payment.
     *
     * <p>Returns false when the order is already paid rather than throwing.
     * Webhook delivery is at-least-once, so a repeat is expected traffic, not an
     * error — and the caller needs to know not to fulfil twice.
     */
    public boolean markPaid(String gatewayPaymentId, Instant when) {
        if (status == OrderStatus.PAID) {
            return false;
        }
        if (!status.canTransitionTo(OrderStatus.PAID)) {
            throw new BadRequestException("Cannot mark a " + status + " order as paid");
        }
        this.status = OrderStatus.PAID;
        this.gatewayPaymentId = gatewayPaymentId;
        this.paidAt = when;
        this.failureReason = null;
        return true;
    }

    public boolean markFailed(String reason) {
        if (status.isTerminal()) {
            return false;
        }
        this.status = OrderStatus.FAILED;
        this.failureReason = reason != null && reason.length() > 400 ? reason.substring(0, 400) : reason;
        return true;
    }

    public boolean isPaid() {
        return status == OrderStatus.PAID;
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

    public UUID getPropertyId() {
        return propertyId;
    }

    public String getCity() {
        return city;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getGateway() {
        return gateway;
    }

    public String getGatewayOrderId() {
        return gatewayOrderId;
    }

    public String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PaymentOrder order) || id == null) {
            return false;
        }
        return id.equals(order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
