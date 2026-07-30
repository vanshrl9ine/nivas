package com.nivas.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nivas.auth.domain.User;
import com.nivas.common.AuditableEntity;
import com.nivas.common.exception.BadRequestException;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * The listing aggregate root — the single place where a listing's invariants live.
 *
 * <p>State changes go through the named methods on this class ({@link #publish},
 * {@link #markSold}, …) rather than open setters. Anything that changes what a
 * searcher would see also bumps {@link #searchRelevantVersion}, which is what the
 * service layer uses to decide whether an outbox event is warranted. Editing a
 * typo in the description should not cost a reindex.
 */
@Entity
@Table(name = "properties")
public class Property extends AuditableEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locality_id")
    private Locality locality;

    @Column(name = "title", nullable = false, length = 180)
    private String title;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "listing_type", nullable = false, length = 16)
    private ListingType listingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 24)
    private PropertyType propertyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PropertyStatus status = PropertyStatus.DRAFT;

    @Column(name = "bedrooms")
    private Short bedrooms;

    @Column(name = "bathrooms")
    private Short bathrooms;

    @Column(name = "balconies")
    private Short balconies;

    @Enumerated(EnumType.STRING)
    @Column(name = "furnishing", length = 16)
    private Furnishing furnishing;

    @Column(name = "carpet_area_sqft")
    private Integer carpetAreaSqft;

    @Column(name = "built_up_area_sqft")
    private Integer builtUpAreaSqft;

    @Column(name = "floor_number")
    private Short floorNumber;

    @Column(name = "total_floors")
    private Short totalFloors;

    @Column(name = "age_years")
    private Short ageYears;

    @Column(name = "price", nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "maintenance_monthly", precision = 12, scale = 2)
    private BigDecimal maintenanceMonthly;

    @Column(name = "security_deposit", precision = 14, scale = 2)
    private BigDecimal securityDeposit;

    @Column(name = "price_negotiable", nullable = false)
    private boolean priceNegotiable;

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "address_line")
    private String addressLine;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "state", nullable = false, length = 80)
    private String state;

    @Column(name = "pincode", length = 12)
    private String pincode;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "rera_id", length = 64)
    private String reraId;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "published_at")
    private Instant publishedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "property_amenities", joinColumns = @JoinColumn(name = "property_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "amenity", nullable = false, length = 32)
    private Set<Amenity> amenities = EnumSet.noneOf(Amenity.class);

    @OneToMany(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<PropertyImage> images = new ArrayList<>();

    /**
     * Bumped by any mutation a searcher could notice. Not persisted — it exists
     * only for the lifetime of the transaction that mutated this instance, which
     * is exactly when the service needs to decide whether to enqueue a reindex.
     */
    @jakarta.persistence.Transient
    private boolean searchRelevantVersion;

    protected Property() {
        // for JPA
    }

    public Property(User owner, String title, ListingType listingType, PropertyType propertyType,
            BigDecimal price, String city, String state) {
        this.owner = owner;
        this.title = title;
        this.listingType = listingType;
        this.propertyType = propertyType;
        this.price = price;
        this.city = city;
        this.state = state;
    }

    // -- lifecycle -----------------------------------------------------------

    /**
     * Moves the listing into the given status, rejecting transitions the state
     * machine does not allow.
     */
    public void transitionTo(PropertyStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BadRequestException(
                    "Cannot move a listing from " + status + " to " + target);
        }
        if (status == target) {
            return;
        }
        // The DB enforces this too (ck_properties_published); setting it here
        // means the constraint never actually fires in normal operation.
        if (target == PropertyStatus.ACTIVE && publishedAt == null) {
            publishedAt = Instant.now();
        }
        this.status = target;
        this.searchRelevantVersion = true;
    }

    public void publish() {
        requireComplete();
        transitionTo(PropertyStatus.ACTIVE);
    }

    public void markSold() {
        transitionTo(PropertyStatus.SOLD);
    }

    public void markRented() {
        transitionTo(PropertyStatus.RENTED);
    }

    public void archive() {
        transitionTo(PropertyStatus.ARCHIVED);
    }

    /**
     * A listing must be good enough to show a stranger before it can go live.
     * Enforced on publish rather than on save so drafts can be saved half-finished.
     */
    private void requireComplete() {
        if (price == null || price.signum() <= 0) {
            throw new BadRequestException("A listing needs a price above zero before it can be published");
        }
        if (latitude == null || longitude == null) {
            throw new BadRequestException("A listing needs coordinates before it can be published");
        }
        if (images.isEmpty()) {
            throw new BadRequestException("A listing needs at least one photo before it can be published");
        }
        if (!propertyType.isLand() && !propertyType.isCommercial() && bedrooms == null) {
            throw new BadRequestException("A residential listing needs a bedroom count before it can be published");
        }
    }

    public void verify() {
        this.verified = true;
        this.verifiedAt = Instant.now();
        this.searchRelevantVersion = true;
    }

    // -- mutations -----------------------------------------------------------

    public void updateBasics(String title, String description, PropertyType propertyType, ListingType listingType) {
        this.searchRelevantVersion |= !Objects.equals(this.title, title)
                || this.propertyType != propertyType
                || this.listingType != listingType;
        this.title = title;
        this.description = description;
        this.propertyType = propertyType;
        this.listingType = listingType;
    }

    public void updatePricing(BigDecimal price, BigDecimal maintenanceMonthly, BigDecimal securityDeposit,
            boolean priceNegotiable) {
        this.searchRelevantVersion |= this.price == null || this.price.compareTo(price) != 0;
        this.price = price;
        this.maintenanceMonthly = maintenanceMonthly;
        this.securityDeposit = securityDeposit;
        this.priceNegotiable = priceNegotiable;
    }

    public void updateLayout(Short bedrooms, Short bathrooms, Short balconies, Furnishing furnishing,
            Integer carpetAreaSqft, Integer builtUpAreaSqft, Short floorNumber, Short totalFloors, Short ageYears) {
        if (floorNumber != null && totalFloors != null && floorNumber > totalFloors) {
            throw new BadRequestException("Floor number cannot be above the building's total floors");
        }
        this.searchRelevantVersion = true;
        this.bedrooms = bedrooms;
        this.bathrooms = bathrooms;
        this.balconies = balconies;
        this.furnishing = furnishing;
        this.carpetAreaSqft = carpetAreaSqft;
        this.builtUpAreaSqft = builtUpAreaSqft;
        this.floorNumber = floorNumber;
        this.totalFloors = totalFloors;
        this.ageYears = ageYears;
    }

    public void updateLocation(Locality locality, String addressLine, String city, String state, String pincode,
            Double latitude, Double longitude) {
        this.searchRelevantVersion = true;
        this.locality = locality;
        this.addressLine = addressLine;
        this.city = city;
        this.state = state;
        this.pincode = pincode;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void replaceAmenities(Set<Amenity> replacement) {
        Set<Amenity> next = replacement.isEmpty() ? EnumSet.noneOf(Amenity.class) : EnumSet.copyOf(replacement);
        this.searchRelevantVersion |= !this.amenities.equals(next);
        this.amenities = next;
    }

    /**
     * Swaps the whole photo set. Positions are reassigned densely from zero so the
     * {@code uq_property_images_position} constraint can never be violated by a
     * caller sending sparse or duplicated positions.
     */
    public void replaceImages(List<PropertyImage> replacement) {
        this.images.clear();
        short position = 0;
        for (PropertyImage image : replacement) {
            image.setProperty(this);
            image.setPosition(position++);
            this.images.add(image);
        }
        this.searchRelevantVersion = true;
    }

    public void addImage(PropertyImage image) {
        image.setProperty(this);
        image.setPosition((short) this.images.size());
        this.images.add(image);
        this.searchRelevantVersion = true;
    }

    /**
     * Applies an aggregated view delta drained from Redis.
     *
     * <p>Deliberately does <em>not</em> set {@link #searchRelevantVersion}: view
     * counts change constantly and reindexing on every increment would swamp the
     * pipeline for a signal that only matters in aggregate.
     */
    public void applyViewDelta(long delta) {
        if (delta > 0) {
            this.viewCount += delta;
        }
    }

    // -- derived -------------------------------------------------------------

    /** Effective area, preferring carpet area, which is what buyers actually compare. */
    public Integer effectiveAreaSqft() {
        return carpetAreaSqft != null ? carpetAreaSqft : builtUpAreaSqft;
    }

    public BigDecimal pricePerSqft() {
        Integer area = effectiveAreaSqft();
        if (area == null || area <= 0 || price == null) {
            return null;
        }
        return price.divide(BigDecimal.valueOf(area), 2, RoundingMode.HALF_UP);
    }

    public PropertyImage coverImage() {
        return images.stream().min(Comparator.comparingInt(PropertyImage::getPosition)).orElse(null);
    }

    public boolean isOwnedBy(UUID userId) {
        return owner != null && owner.getId() != null && owner.getId().equals(userId);
    }

    public boolean needsReindex() {
        return searchRelevantVersion;
    }

    // -- accessors -----------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public Locality getLocality() {
        return locality;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public ListingType getListingType() {
        return listingType;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public PropertyStatus getStatus() {
        return status;
    }

    public Short getBedrooms() {
        return bedrooms;
    }

    public Short getBathrooms() {
        return bathrooms;
    }

    public Short getBalconies() {
        return balconies;
    }

    public Furnishing getFurnishing() {
        return furnishing;
    }

    public Integer getCarpetAreaSqft() {
        return carpetAreaSqft;
    }

    public Integer getBuiltUpAreaSqft() {
        return builtUpAreaSqft;
    }

    public Short getFloorNumber() {
        return floorNumber;
    }

    public Short getTotalFloors() {
        return totalFloors;
    }

    public Short getAgeYears() {
        return ageYears;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getMaintenanceMonthly() {
        return maintenanceMonthly;
    }

    public BigDecimal getSecurityDeposit() {
        return securityDeposit;
    }

    public boolean isPriceNegotiable() {
        return priceNegotiable;
    }

    public LocalDate getAvailableFrom() {
        return availableFrom;
    }

    public void setAvailableFrom(LocalDate availableFrom) {
        this.availableFrom = availableFrom;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPincode() {
        return pincode;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public String getReraId() {
        return reraId;
    }

    public void setReraId(String reraId) {
        this.reraId = reraId;
    }

    public boolean isVerified() {
        return verified;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public long getViewCount() {
        return viewCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Set<Amenity> getAmenities() {
        return amenities;
    }

    public List<PropertyImage> getImages() {
        return images;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Property property) || id == null) {
            return false;
        }
        return id.equals(property.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
