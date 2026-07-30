package com.nivas.search;

import java.math.BigDecimal;
import java.util.List;

import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.Furnishing;
import com.nivas.catalog.domain.ListingType;
import com.nivas.catalog.domain.PropertyType;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SortOption;

/**
 * Test-only builder for {@link SearchRequest}.
 *
 * <p>The record has twenty-plus components, nearly all nullable. Constructing it
 * positionally in a test is unreadable and, worse, silently wrong the moment a
 * component is inserted — every literal shifts one place and the test keeps
 * compiling. Naming the fields here makes the tests state only what they mean.
 */
public final class SearchRequestBuilder {

    private String q;
    private String city;
    private List<String> localitySlugs;
    private ListingType listingType;
    private List<PropertyType> propertyTypes;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private List<Short> bedrooms;
    private Integer minAreaSqft;
    private Integer maxAreaSqft;
    private List<Furnishing> furnishing;
    private List<Amenity> amenities;
    private Boolean verifiedOnly;
    private java.time.Instant publishedAfter;
    private Double lat;
    private Double lon;
    private Double radiusKm;
    private Double topLeftLat;
    private Double topLeftLon;
    private Double bottomRightLat;
    private Double bottomRightLon;
    private SortOption sort;
    private Integer page;
    private Integer size;

    public static SearchRequestBuilder request() {
        return new SearchRequestBuilder();
    }

    public SearchRequestBuilder q(String value) {
        this.q = value;
        return this;
    }

    public SearchRequestBuilder city(String value) {
        this.city = value;
        return this;
    }

    public SearchRequestBuilder listingType(ListingType value) {
        this.listingType = value;
        return this;
    }

    public SearchRequestBuilder propertyTypes(PropertyType... values) {
        this.propertyTypes = List.of(values);
        return this;
    }

    public SearchRequestBuilder price(String min, String max) {
        this.minPrice = min == null ? null : new BigDecimal(min);
        this.maxPrice = max == null ? null : new BigDecimal(max);
        return this;
    }

    public SearchRequestBuilder bedrooms(Short... values) {
        this.bedrooms = List.of(values);
        return this;
    }

    public SearchRequestBuilder amenities(Amenity... values) {
        this.amenities = List.of(values);
        return this;
    }

    public SearchRequestBuilder verifiedOnly(boolean value) {
        this.verifiedOnly = value;
        return this;
    }

    public SearchRequestBuilder publishedAfter(java.time.Instant value) {
        this.publishedAfter = value;
        return this;
    }

    public SearchRequestBuilder point(double lat, double lon, double radiusKm) {
        this.lat = lat;
        this.lon = lon;
        this.radiusKm = radiusKm;
        return this;
    }

    public SearchRequestBuilder boundingBox(double topLeftLat, double topLeftLon,
            double bottomRightLat, double bottomRightLon) {
        this.topLeftLat = topLeftLat;
        this.topLeftLon = topLeftLon;
        this.bottomRightLat = bottomRightLat;
        this.bottomRightLon = bottomRightLon;
        return this;
    }

    public SearchRequestBuilder sort(SortOption value) {
        this.sort = value;
        return this;
    }

    public SearchRequest build() {
        return new SearchRequest(q, city, localitySlugs, listingType, propertyTypes,
                minPrice, maxPrice, bedrooms, minAreaSqft, maxAreaSqft, furnishing, amenities,
                verifiedOnly, publishedAfter, lat, lon, radiusKm,
                topLeftLat, topLeftLon, bottomRightLat, bottomRightLon,
                sort, page, size);
    }
}
