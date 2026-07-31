package com.nivas.search.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;
import org.springframework.data.elasticsearch.annotations.Mapping;
import org.springframework.data.elasticsearch.annotations.Setting;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.suggest.Completion;

/**
 * The read model: one flat document per searchable listing.
 *
 * <p>Three annotations here carry more weight than they look:
 *
 * <ul>
 * <li>{@code versionType = EXTERNAL_GTE} — the version below is the listing's JPA
 * version, not an Elasticsearch-managed counter. Elasticsearch rejects any write
 * carrying an <em>older</em> version than it already holds, so a re-delivered or
 * out-of-order event cannot resurrect stale data.
 * <p>
 * GTE rather than EXTERNAL, and the difference is not cosmetic. Plain EXTERNAL
 * demands a strictly greater version, which makes re-writing a document at its
 * current version impossible — and that is exactly what a full reindex does.
 * {@code POST /admin/index/rebuild} failed on every document with "current
 * version [3] is higher or equal to the one provided [3]", so the recovery path
 * only ever worked against an empty index. Allowing equality makes redelivery
 * idempotent instead of fatal, while still rejecting anything genuinely stale.
 * <li>{@code writeTypeHint = FALSE} — Spring Data otherwise writes a {@code _class}
 * field into every document. The mapping is {@code dynamic: strict}, so that
 * field would be rejected on every single index operation.
 * <li>{@code @Mapping}/{@code @Setting} — the mapping and analyzers are authored
 * as JSON rather than derived from annotations. Multi-fields, edge-ngram
 * autocomplete, synonym graphs and completion contexts have no faithful
 * annotation equivalent, and search quality lives in exactly those details.
 * </ul>
 */
@Document(
        indexName = PropertyDocument.INDEX_NAME,
        createIndex = true,
        versionType = Document.VersionType.EXTERNAL_GTE,
        writeTypeHint = WriteTypeHint.FALSE,
        // Keeps the version in Elasticsearch's own _version metadata instead of
        // copying it into _source. Left at its default, Spring Data writes a
        // "version" field into every document, the strict mapping rejects it, and
        // *every* index operation fails with strict_dynamic_mapping_exception.
        storeVersionInSource = false)
@Setting(settingPath = "elasticsearch/property-settings.json")
@Mapping(mappingPath = "elasticsearch/property-mapping.json")
public class PropertyDocument {

    /**
     * Annotation values must be compile-time constants, so this is the one place
     * the index name is defined. A zero-downtime reindex would point a
     * {@code properties} alias at {@code properties_v2} rather than change this.
     */
    public static final String INDEX_NAME = "properties";

    @Id
    private String id;

    /**
     * Mirrors {@code properties.version} in Postgres. See the class javadoc — this
     * is the mechanism that makes at-least-once delivery safe.
     */
    @Version
    private Long version;

    @Field(type = FieldType.Keyword)
    private String ownerId;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Keyword)
    private String listingType;

    @Field(type = FieldType.Keyword)
    private String propertyType;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Keyword)
    private String furnishing;

    @Field(type = FieldType.Keyword)
    private List<String> amenities;

    @Field(type = FieldType.Short)
    private Short bedrooms;

    @Field(type = FieldType.Short)
    private Short bathrooms;

    @Field(type = FieldType.Short)
    private Short balconies;

    @Field(type = FieldType.Short)
    private Short floorNumber;

    @Field(type = FieldType.Short)
    private Short totalFloors;

    @Field(type = FieldType.Short)
    private Short ageYears;

    @Field(type = FieldType.Integer)
    private Integer carpetAreaSqft;

    @Field(type = FieldType.Integer)
    private Integer builtUpAreaSqft;

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal price;

    /** Precomputed at index time: sorting on a script would be far slower. */
    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal pricePerSqft;

    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal maintenanceMonthly;

    @Field(type = FieldType.Boolean)
    private Boolean priceNegotiable;

    @Field(type = FieldType.Keyword)
    private String localityId;

    @Field(type = FieldType.Keyword)
    private String localitySlug;

    @Field(type = FieldType.Keyword)
    private String localityName;

    @Field(type = FieldType.Keyword)
    private String city;

    @Field(type = FieldType.Keyword)
    private String state;

    @Field(type = FieldType.Keyword)
    private String pincode;

    @Field(type = FieldType.Text)
    private String addressLine;

    @GeoPointField
    private GeoPoint location;

    @Field(type = FieldType.Boolean)
    private Boolean verified;

    @Field(type = FieldType.Long)
    private Long viewCount;

    @Field(type = FieldType.Integer)
    private Integer imageCount;

    @Field(type = FieldType.Keyword, index = false)
    private String coverImageUrl;

    @Field(type = FieldType.Date, format = DateFormat.date)
    private LocalDate availableFrom;

    @Field(type = FieldType.Date, format = DateFormat.date_optional_time)
    private Instant publishedAt;

    @Field(type = FieldType.Date, format = DateFormat.date_optional_time)
    private Instant updatedAt;

    /** Feeds the type-ahead endpoint; the city context lets it be scoped per city. */
    @Field(type = FieldType.Auto)
    private Completion suggest;

    public PropertyDocument() {
        // for the mapping converter
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getListingType() {
        return listingType;
    }

    public void setListingType(String listingType) {
        this.listingType = listingType;
    }

    public String getPropertyType() {
        return propertyType;
    }

    public void setPropertyType(String propertyType) {
        this.propertyType = propertyType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFurnishing() {
        return furnishing;
    }

    public void setFurnishing(String furnishing) {
        this.furnishing = furnishing;
    }

    public List<String> getAmenities() {
        return amenities;
    }

    public void setAmenities(List<String> amenities) {
        this.amenities = amenities;
    }

    public Short getBedrooms() {
        return bedrooms;
    }

    public void setBedrooms(Short bedrooms) {
        this.bedrooms = bedrooms;
    }

    public Short getBathrooms() {
        return bathrooms;
    }

    public void setBathrooms(Short bathrooms) {
        this.bathrooms = bathrooms;
    }

    public Short getBalconies() {
        return balconies;
    }

    public void setBalconies(Short balconies) {
        this.balconies = balconies;
    }

    public Short getFloorNumber() {
        return floorNumber;
    }

    public void setFloorNumber(Short floorNumber) {
        this.floorNumber = floorNumber;
    }

    public Short getTotalFloors() {
        return totalFloors;
    }

    public void setTotalFloors(Short totalFloors) {
        this.totalFloors = totalFloors;
    }

    public Short getAgeYears() {
        return ageYears;
    }

    public void setAgeYears(Short ageYears) {
        this.ageYears = ageYears;
    }

    public Integer getCarpetAreaSqft() {
        return carpetAreaSqft;
    }

    public void setCarpetAreaSqft(Integer carpetAreaSqft) {
        this.carpetAreaSqft = carpetAreaSqft;
    }

    public Integer getBuiltUpAreaSqft() {
        return builtUpAreaSqft;
    }

    public void setBuiltUpAreaSqft(Integer builtUpAreaSqft) {
        this.builtUpAreaSqft = builtUpAreaSqft;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getPricePerSqft() {
        return pricePerSqft;
    }

    public void setPricePerSqft(BigDecimal pricePerSqft) {
        this.pricePerSqft = pricePerSqft;
    }

    public BigDecimal getMaintenanceMonthly() {
        return maintenanceMonthly;
    }

    public void setMaintenanceMonthly(BigDecimal maintenanceMonthly) {
        this.maintenanceMonthly = maintenanceMonthly;
    }

    public Boolean getPriceNegotiable() {
        return priceNegotiable;
    }

    public void setPriceNegotiable(Boolean priceNegotiable) {
        this.priceNegotiable = priceNegotiable;
    }

    public String getLocalityId() {
        return localityId;
    }

    public void setLocalityId(String localityId) {
        this.localityId = localityId;
    }

    public String getLocalitySlug() {
        return localitySlug;
    }

    public void setLocalitySlug(String localitySlug) {
        this.localitySlug = localitySlug;
    }

    public String getLocalityName() {
        return localityName;
    }

    public void setLocalityName(String localityName) {
        this.localityName = localityName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public GeoPoint getLocation() {
        return location;
    }

    public void setLocation(GeoPoint location) {
        this.location = location;
    }

    public Boolean getVerified() {
        return verified;
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    public Integer getImageCount() {
        return imageCount;
    }

    public void setImageCount(Integer imageCount) {
        this.imageCount = imageCount;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public LocalDate getAvailableFrom() {
        return availableFrom;
    }

    public void setAvailableFrom(LocalDate availableFrom) {
        this.availableFrom = availableFrom;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Completion getSuggest() {
        return suggest;
    }

    public void setSuggest(Completion suggest) {
        this.suggest = suggest;
    }
}
