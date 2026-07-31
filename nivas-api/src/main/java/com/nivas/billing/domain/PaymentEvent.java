package com.nivas.billing.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A webhook, recorded verbatim before anything acts on it.
 *
 * <p>This table is the audit trail that settles arguments. When somebody insists
 * they paid and the system disagrees, the payload as the gateway sent it — along
 * with whether its signature checked out — is the only thing that answers the
 * question.
 *
 * <p>The unique constraint on {@code (gateway, event_id)} is also the idempotency
 * mechanism. Gateways deliver at-least-once, and the second delivery must not
 * grant the package twice.
 */
@Entity
@Table(name = "payment_events")
@EntityListeners(AuditingEntityListener.class)
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "gateway", nullable = false, length = 24)
    private String gateway;

    @Column(name = "event_id", nullable = false, length = 120)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "order_id")
    private UUID orderId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /**
     * Recorded rather than enforced at this layer.
     *
     * <p>An event with a bad signature is still written down — a forged or
     * misconfigured webhook is exactly the thing worth being able to look at
     * afterwards. It simply is not acted upon.
     */
    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(name = "processed", nullable = false)
    private boolean processed;

    @Column(name = "processing_error")
    private String processingError;

    @CreatedDate
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected PaymentEvent() {
        // for JPA
    }

    public PaymentEvent(String gateway, String eventId, String eventType, String payload,
            boolean signatureValid) {
        this.gateway = gateway;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.signatureValid = signatureValid;
    }

    public void markProcessed(UUID orderId) {
        this.orderId = orderId;
        this.processed = true;
        this.processingError = null;
    }

    public void markFailed(String error) {
        this.processed = false;
        this.processingError = error != null && error.length() > 2000 ? error.substring(0, 2000) : error;
    }

    public Long getId() {
        return id;
    }

    public String getGateway() {
        return gateway;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isSignatureValid() {
        return signatureValid;
    }

    public boolean isProcessed() {
        return processed;
    }

    public String getProcessingError() {
        return processingError;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PaymentEvent event) || id == null) {
            return false;
        }
        return id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
