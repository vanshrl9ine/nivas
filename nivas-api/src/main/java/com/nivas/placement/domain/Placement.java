package com.nivas.placement.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.nivas.common.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One listing occupying one slot, for a period.
 *
 * <p>A booking that spans several slots is several rows. That is what lets an
 * audience maximizer be expressed as ordinary inventory: it books every slot on
 * the city home surfaces, and the exclusion constraint then makes it exclusive
 * without any code knowing the concept exists.
 *
 * <p>The period is stored as a Postgres {@code daterange} and mapped as two
 * columns here — Hibernate has no native range type, and the exclusion
 * constraint needs the real thing. Writes go through
 * {@code PlacementRepository.book}, which builds the range in SQL.
 */
@Entity
@Table(name = "placements")
public class Placement extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "surface", nullable = false, length = 32)
    private Surface surface;

    /** Empty string for national surfaces — see {@link Surface#cityKey}. */
    @Column(name = "city", nullable = false, length = 80)
    private String city = "";

    @Column(name = "slot_index", nullable = false)
    private short slotIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PlacementStatus status = PlacementStatus.ACTIVE;

    /**
     * Read-only projections of the {@code period} daterange, so the entity can
     * report its dates without Hibernate needing to understand the type.
     */
    @Column(name = "starts_on", insertable = false, updatable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", insertable = false, updatable = false)
    private LocalDate endsOn;

    protected Placement() {
        // for JPA
    }

    public void cancel() {
        // The exclusion constraint only covers ACTIVE rows, so this genuinely
        // returns the slot to inventory rather than merely hiding it.
        this.status = PlacementStatus.CANCELLED;
    }

    public boolean isLiveOn(LocalDate day) {
        return status == PlacementStatus.ACTIVE
                && startsOn != null && endsOn != null
                && !day.isBefore(startsOn) && day.isBefore(endsOn);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPropertyId() {
        return propertyId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPackageId() {
        return packageId;
    }

    public Surface getSurface() {
        return surface;
    }

    public String getCity() {
        return city;
    }

    public short getSlotIndex() {
        return slotIndex;
    }

    public PlacementStatus getStatus() {
        return status;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Placement placement) || id == null) {
            return false;
        }
        return id.equals(placement.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
