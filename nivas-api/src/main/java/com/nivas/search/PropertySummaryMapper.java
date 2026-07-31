package com.nivas.search;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.data.elasticsearch.core.SearchHit;

import com.nivas.search.document.PropertyDocument;
import com.nivas.search.dto.SearchDtos.PropertySummary;

/**
 * Turns an indexed document into the summary shape every result list uses.
 *
 * <p>Shared rather than duplicated because search, trending and similar-listings
 * all return the same card. A second copy would drift the moment a field is
 * added, and the symptom would be one surface quietly missing data.
 */
final class PropertySummaryMapper {

    private PropertySummaryMapper() {
    }

    /** For results that carry no distance or relevance score of their own. */
    static PropertySummary toSummary(PropertyDocument doc) {
        return toSummary(doc, null, null);
    }

    /**
     * For search hits.
     *
     * <p>{@code distanceKm} is read from the sort values rather than recomputed:
     * when sorting by distance Elasticsearch has already calculated it, in the
     * unit requested, and recomputing would be both slower and subtly different.
     */
    static PropertySummary fromHit(SearchHit<PropertyDocument> hit, boolean sortedByDistance) {
        Double distanceKm = null;

        if (sortedByDistance && !hit.getSortValues().isEmpty()
                && hit.getSortValues().get(0) instanceof Number distance) {
            double value = distance.doubleValue();
            // Documents with no location sort last with a sentinel of +Infinity.
            distanceKm = Double.isFinite(value)
                    ? BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue()
                    : null;
        }

        Double score = Float.isNaN(hit.getScore()) ? null : (double) hit.getScore();
        return toSummary(hit.getContent(), distanceKm, score);
    }

    private static PropertySummary toSummary(PropertyDocument doc, Double distanceKm, Double score) {
        // Carpet area is what buyers compare; built-up is the fallback.
        Integer area = doc.getCarpetAreaSqft() != null ? doc.getCarpetAreaSqft() : doc.getBuiltUpAreaSqft();

        return new PropertySummary(
                doc.getId(),
                doc.getTitle(),
                doc.getListingType(),
                doc.getPropertyType(),
                doc.getPrice(),
                doc.getPricePerSqft(),
                doc.getBedrooms(),
                doc.getBathrooms(),
                area,
                doc.getFurnishing(),
                doc.getLocalityName(),
                doc.getCity(),
                doc.getLocation() == null ? null : doc.getLocation().getLat(),
                doc.getLocation() == null ? null : doc.getLocation().getLon(),
                doc.getVerified(),
                doc.getImageCount(),
                doc.getCoverImageUrl(),
                doc.getViewCount(),
                doc.getPublishedAt(),
                distanceKm,
                score);
    }
}
