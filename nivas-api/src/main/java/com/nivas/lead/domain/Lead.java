package com.nivas.lead.domain;

import java.util.Objects;
import java.util.UUID;

import com.nivas.auth.domain.User;
import com.nivas.catalog.domain.Property;
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
 * An enquiry against a listing.
 *
 * <p>Leads are the product's revenue event, which is why the table carries an
 * idempotency key: a double-tapped "Contact owner" button must not bill the
 * owner twice or show them a duplicate.
 */
@Entity
@Table(name = "leads")
public class Lead extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    /** Null for enquiries from signed-out visitors. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seeker_id")
    private User seeker;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "message", length = 1000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LeadStatus status = LeadStatus.NEW;

    @Column(name = "source", nullable = false, length = 32)
    private String source = "WEB";

    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    protected Lead() {
        // for JPA
    }

    public Lead(Property property, User seeker, String name, String email, String phone, String message,
            String source, String idempotencyKey) {
        this.property = property;
        this.seeker = seeker;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.message = message;
        this.source = source == null ? "WEB" : source;
        this.idempotencyKey = idempotencyKey;
    }

    public void moveTo(LeadStatus target) {
        this.status = target;
    }

    public UUID getId() {
        return id;
    }

    public Property getProperty() {
        return property;
    }

    public User getSeeker() {
        return seeker;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getMessage() {
        return message;
    }

    public LeadStatus getStatus() {
        return status;
    }

    public String getSource() {
        return source;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Lead lead) || id == null) {
            return false;
        }
        return id.equals(lead.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
