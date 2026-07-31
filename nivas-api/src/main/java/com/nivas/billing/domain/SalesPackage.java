package com.nivas.billing.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nivas.placement.domain.Surface;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;

/**
 * Something an owner can buy.
 *
 * <p>Named {@code SalesPackage} rather than {@code Package} because
 * {@code java.lang.Package} exists and shadowing it in every importing class is
 * a small, permanent tax.
 *
 * <p>Prices are held in paise as a {@code long}. Payment gateways work in minor
 * units, and keeping the same representation end to end means there is never a
 * rounding difference between what we charged and what they captured.
 */
@Entity
@Table(name = "packages")
public class SalesPackage {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, length = 48)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "description", length = 400)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_type", nullable = false, length = 24)
    private PackageType packageType;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "listing_quota")
    private Integer listingQuota;

    @Column(name = "auto_verify", nullable = false)
    private boolean autoVerify;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * How many slots this package books on each surface.
     *
     * <p>A map rather than a single surface because an audience maximizer spans
     * two — the hero and the whole featured rail — and expressing that as one
     * surface plus a special case is how the special case ends up everywhere.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "package_surfaces", joinColumns = @JoinColumn(name = "package_id"))
    @MapKeyColumn(name = "surface")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "slot_count", nullable = false)
    private Map<Surface, Short> slotsPerSurface = new HashMap<>();

    protected SalesPackage() {
        // for JPA
    }

    public boolean isFree() {
        return pricePaise == 0;
    }

    public Duration duration() {
        return Duration.ofDays(durationDays);
    }

    /** Rupees, for display only. Never use this for anything sent to a gateway. */
    public BigDecimal priceInRupees() {
        return BigDecimal.valueOf(pricePaise).movePointLeft(2);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PackageType getPackageType() {
        return packageType;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public String getCurrency() {
        return currency;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public Integer getListingQuota() {
        return listingQuota;
    }

    public boolean isAutoVerify() {
        return autoVerify;
    }

    public short getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public Map<Surface, Short> getSlotsPerSurface() {
        return slotsPerSurface;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SalesPackage salesPackage) || id == null) {
            return false;
        }
        return id.equals(salesPackage.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
