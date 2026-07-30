package com.nivas.search.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.Furnishing;
import com.nivas.catalog.domain.ListingType;
import com.nivas.catalog.domain.PropertyType;
import com.nivas.common.PageResponse;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class SearchDtos {

    private SearchDtos() {
    }

    /**
     * Every filter the search endpoint understands.
     *
     * <p>Bound from query parameters, so it is a flat record rather than a nested
     * object graph — a URL is the thing users share, bookmark and paste into
     * support tickets, and it should stay legible.
     */
    public record SearchRequest(
            @Size(max = 200) String q,

            String city,
            List<String> localitySlugs,

            ListingType listingType,
            List<PropertyType> propertyTypes,

            @DecimalMin("0") BigDecimal minPrice,
            @DecimalMin("0") BigDecimal maxPrice,

            /** Matches any of the given counts; {@code 4} means "4 or more". */
            List<Short> bedrooms,

            @Min(0) Integer minAreaSqft,
            @Min(0) Integer maxAreaSqft,

            List<Furnishing> furnishing,

            /** ALL of these must be present, not any — a filter, not a preference. */
            List<Amenity> amenities,

            Boolean verifiedOnly,

            /**
             * Only listings published at or after this instant. Not exposed as a
             * user-facing filter — it is what lets a saved-search alert ask
             * "what is new since I last told this user about it?".
             */
            Instant publishedAfter,

            // Point-radius search. All three are required together.
            @Min(-90) @Max(90) Double lat,
            @Min(-180) @Max(180) Double lon,
            @DecimalMin("0.1") Double radiusKm,

            // Map viewport search, as sent by a "search this area" button.
            Double topLeftLat,
            Double topLeftLon,
            Double bottomRightLat,
            Double bottomRightLon,

            SortOption sort,

            @Min(0) Integer page,
            @Min(1) @Max(100) Integer size) {

        public boolean hasText() {
            return q != null && !q.isBlank();
        }

        public boolean hasGeoPoint() {
            return lat != null && lon != null && radiusKm != null;
        }

        public boolean hasBoundingBox() {
            return topLeftLat != null && topLeftLon != null && bottomRightLat != null && bottomRightLon != null;
        }

        public SortOption effectiveSort() {
            if (sort != null) {
                return sort;
            }
            // With no query text every document scores the same, so relevance
            // order would be arbitrary. Newest-first is the sane default there.
            return hasText() ? SortOption.RELEVANCE : SortOption.NEWEST;
        }

        public int pageOrDefault() {
            return page == null ? 0 : page;
        }

        public int sizeOrDefault(int fallback) {
            return size == null ? fallback : size;
        }
    }

    public enum SortOption {
        RELEVANCE,
        PRICE_ASC,
        PRICE_DESC,
        AREA_DESC,
        NEWEST,
        /** Only meaningful alongside a geo point; falls back to relevance otherwise. */
        DISTANCE
    }

    /** A listing as it appears in a result list — deliberately smaller than the detail view. */
    public record PropertySummary(
            String id,
            String title,
            String listingType,
            String propertyType,
            BigDecimal price,
            BigDecimal pricePerSqft,
            Short bedrooms,
            Short bathrooms,
            Integer areaSqft,
            String furnishing,
            String localityName,
            String city,
            Double latitude,
            Double longitude,
            Boolean verified,
            Integer imageCount,
            String coverImageUrl,
            Long viewCount,
            Instant publishedAt,
            /** Kilometres from the requested point; null unless the search had one. */
            Double distanceKm,
            Double score) {
    }

    public record FacetBucket(String key, String label, long count) {
    }

    public record PriceStats(BigDecimal min, BigDecimal max, BigDecimal avg) {
    }

    public record SearchResponse(
            PageResponse<PropertySummary> results,
            /** Facet name to its buckets, e.g. {@code propertyType -> [APARTMENT (42), VILLA (7)]}. */
            Map<String, List<FacetBucket>> facets,
            PriceStats priceStats,
            long tookMillis) {
    }

    public record SuggestionResponse(List<Suggestion> suggestions) {

        public record Suggestion(String text, String propertyId, String city) {
        }
    }
}
