package com.nivas.catalog.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.Furnishing;
import com.nivas.catalog.domain.ListingType;
import com.nivas.catalog.domain.Property;
import com.nivas.catalog.domain.PropertyImage;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.catalog.domain.PropertyType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PropertyDtos {

    private PropertyDtos() {
    }

    public record ImagePayload(
            @NotBlank @Size(max = 2000) String url,
            @Size(max = 180) String caption,
            @Min(1) Integer width,
            @Min(1) Integer height) {
    }

    public record UpsertPropertyRequest(
            @NotBlank @Size(min = 10, max = 180) String title,
            @Size(max = 8000) String description,

            @NotNull ListingType listingType,
            @NotNull PropertyType propertyType,

            @NotNull @DecimalMin(value = "1.0") BigDecimal price,
            @DecimalMin("0.0") BigDecimal maintenanceMonthly,
            @DecimalMin("0.0") BigDecimal securityDeposit,
            boolean priceNegotiable,
            LocalDate availableFrom,

            @Min(0) @Max(50) Short bedrooms,
            @Min(0) @Max(50) Short bathrooms,
            @Min(0) @Max(50) Short balconies,
            Furnishing furnishing,
            @Min(1) Integer carpetAreaSqft,
            @Min(1) Integer builtUpAreaSqft,
            Short floorNumber,
            Short totalFloors,
            @Min(0) @Max(200) Short ageYears,

            UUID localityId,
            @Size(max = 255) String addressLine,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @Size(max = 12) String pincode,
            @Min(-90) @Max(90) Double latitude,
            @Min(-180) @Max(180) Double longitude,

            @Size(max = 64) String reraId,
            Set<Amenity> amenities,
            @Valid @Size(max = 30) List<ImagePayload> images) {
    }

    public record StatusChangeRequest(@NotNull PropertyStatus status) {
    }

    public record ImageResponse(UUID id, String url, String caption, int position, Integer width, Integer height) {

        static ImageResponse from(PropertyImage image) {
            return new ImageResponse(image.getId(), image.getUrl(), image.getCaption(),
                    image.getPosition(), image.getWidth(), image.getHeight());
        }
    }

    public record LocalityResponse(UUID id, String slug, String name, String city, String state) {
    }

    public record OwnerResponse(UUID id, String fullName, String phone) {
    }

    /**
     * The full listing payload.
     *
     * <p>{@link Serializable} because instances are cached in Redis; the JSON
     * serialiser does not require it, but keeping the marker makes the intent
     * explicit and guards against someone swapping the serialiser later.
     */
    public record PropertyDetailResponse(
            UUID id,
            String title,
            String description,
            ListingType listingType,
            PropertyType propertyType,
            PropertyStatus status,

            BigDecimal price,
            BigDecimal pricePerSqft,
            BigDecimal maintenanceMonthly,
            BigDecimal securityDeposit,
            boolean priceNegotiable,
            LocalDate availableFrom,

            Short bedrooms,
            Short bathrooms,
            Short balconies,
            Furnishing furnishing,
            Integer carpetAreaSqft,
            Integer builtUpAreaSqft,
            Short floorNumber,
            Short totalFloors,
            Short ageYears,

            LocalityResponse locality,
            String addressLine,
            String city,
            String state,
            String pincode,
            Double latitude,
            Double longitude,

            String reraId,
            boolean verified,
            long viewCount,
            Set<Amenity> amenities,
            List<ImageResponse> images,
            OwnerResponse owner,

            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt) implements Serializable {

        public static PropertyDetailResponse from(Property property) {
            var locality = property.getLocality() == null ? null
                    : new LocalityResponse(
                            property.getLocality().getId(),
                            property.getLocality().getSlug(),
                            property.getLocality().getName(),
                            property.getLocality().getCity(),
                            property.getLocality().getState());

            // Contact details are intentionally part of the public payload for a
            // listing: reaching the owner is the point of the product. Email is
            // not exposed — enquiries go through the lead endpoint so they can be
            // rate limited and attributed.
            var owner = new OwnerResponse(
                    property.getOwner().getId(),
                    property.getOwner().getFullName(),
                    property.getOwner().getPhone());

            return new PropertyDetailResponse(
                    property.getId(),
                    property.getTitle(),
                    property.getDescription(),
                    property.getListingType(),
                    property.getPropertyType(),
                    property.getStatus(),
                    property.getPrice(),
                    property.pricePerSqft(),
                    property.getMaintenanceMonthly(),
                    property.getSecurityDeposit(),
                    property.isPriceNegotiable(),
                    property.getAvailableFrom(),
                    property.getBedrooms(),
                    property.getBathrooms(),
                    property.getBalconies(),
                    property.getFurnishing(),
                    property.getCarpetAreaSqft(),
                    property.getBuiltUpAreaSqft(),
                    property.getFloorNumber(),
                    property.getTotalFloors(),
                    property.getAgeYears(),
                    locality,
                    property.getAddressLine(),
                    property.getCity(),
                    property.getState(),
                    property.getPincode(),
                    property.getLatitude(),
                    property.getLongitude(),
                    property.getReraId(),
                    property.isVerified(),
                    property.getViewCount(),
                    Set.copyOf(property.getAmenities()),
                    property.getImages().stream().map(ImageResponse::from).toList(),
                    owner,
                    property.getPublishedAt(),
                    property.getCreatedAt(),
                    property.getUpdatedAt());
        }
    }

    /** Compact shape for an owner's own listing list. */
    public record OwnerListingResponse(
            UUID id,
            String title,
            PropertyStatus status,
            ListingType listingType,
            PropertyType propertyType,
            BigDecimal price,
            String city,
            long viewCount,
            int imageCount,
            Instant publishedAt,
            Instant updatedAt) {

        public static OwnerListingResponse from(Property property) {
            return new OwnerListingResponse(
                    property.getId(),
                    property.getTitle(),
                    property.getStatus(),
                    property.getListingType(),
                    property.getPropertyType(),
                    property.getPrice(),
                    property.getCity(),
                    property.getViewCount(),
                    property.getImages().size(),
                    property.getPublishedAt(),
                    property.getUpdatedAt());
        }
    }
}
