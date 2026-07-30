package com.nivas.search;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregation;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.nivas.common.PageResponse;
import com.nivas.config.NivasProperties;
import com.nivas.search.document.PropertyDocument;
import com.nivas.search.dto.SearchDtos.FacetBucket;
import com.nivas.search.dto.SearchDtos.PriceStats;
import com.nivas.search.dto.SearchDtos.PropertySummary;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SearchResponse;
import com.nivas.search.dto.SearchDtos.SortOption;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StatsAggregate;

@Service
public class PropertySearchService {

    private static final Logger log = LoggerFactory.getLogger(PropertySearchService.class);

    private final ElasticsearchOperations operations;
    private final PropertyQueryBuilder queryBuilder;
    private final NivasProperties properties;

    public PropertySearchService(ElasticsearchOperations operations, PropertyQueryBuilder queryBuilder,
            NivasProperties properties) {
        this.operations = operations;
        this.queryBuilder = queryBuilder;
        this.properties = properties;
    }

    public SearchResponse search(SearchRequest request) {
        long startedAt = System.nanoTime();

        int size = Math.min(request.sizeOrDefault(properties.search().defaultPageSize()),
                properties.search().maxPageSize());
        int page = request.pageOrDefault();

        NativeQueryBuilder builder = new NativeQueryBuilder()
                .withQuery(queryBuilder.build(request))
                .withSort(queryBuilder.sortOptions(request))
                .withPageable(PageRequest.of(page, size))
                // Without this Elasticsearch stops counting at 10 000 and the UI
                // would show "10000+" forever. Accuracy here is cheap and the
                // result count is a headline number on the page.
                .withTrackTotalHits(true);

        queryBuilder.aggregations().forEach(builder::withAggregation);

        NativeQuery query = builder.build();
        SearchHits<PropertyDocument> hits = operations.search(query, PropertyDocument.class);

        boolean sortedByDistance = request.effectiveSort() == SortOption.DISTANCE && request.hasGeoPoint();
        List<PropertySummary> results = hits.getSearchHits().stream()
                .map(hit -> toSummary(hit, sortedByDistance))
                .toList();

        long tookMillis = (System.nanoTime() - startedAt) / 1_000_000;
        Aggregations aggregations = readAggregations(hits);

        return new SearchResponse(
                PageResponse.of(results, page, size, hits.getTotalHits()),
                aggregations.facets(),
                aggregations.priceStats(),
                tookMillis);
    }

    // -- hit mapping ---------------------------------------------------------

    private PropertySummary toSummary(SearchHit<PropertyDocument> hit, boolean sortedByDistance) {
        PropertyDocument doc = hit.getContent();

        // When sorting by distance Elasticsearch has already computed it and
        // returns it as the first sort value, in the unit we requested (km).
        // Recomputing it here would be both slower and subtly different.
        Double distanceKm = null;
        if (sortedByDistance && !hit.getSortValues().isEmpty()
                && hit.getSortValues().get(0) instanceof Number distance) {
            double value = distance.doubleValue();
            // Documents with no location sort last with a sentinel of +Infinity.
            distanceKm = Double.isFinite(value)
                    ? BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue()
                    : null;
        }

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
                Float.isNaN(hit.getScore()) ? null : (double) hit.getScore());
    }

    // -- aggregation parsing -------------------------------------------------

    private record Aggregations(Map<String, List<FacetBucket>> facets, PriceStats priceStats) {
    }

    private Aggregations readAggregations(SearchHits<PropertyDocument> hits) {
        if (!hits.hasAggregations() || !(hits.getAggregations() instanceof ElasticsearchAggregations container)) {
            return new Aggregations(Map.of(), null);
        }

        Map<String, ElasticsearchAggregation> byName = container.aggregationsAsMap();
        Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();
        PriceStats priceStats = null;

        for (Map.Entry<String, ElasticsearchAggregation> entry : byName.entrySet()) {
            Aggregate aggregate = entry.getValue().aggregation().getAggregate();

            if (aggregate.isStats()) {
                priceStats = toPriceStats(aggregate.stats());
                continue;
            }

            List<FacetBucket> buckets = toBuckets(aggregate);
            if (!buckets.isEmpty()) {
                facets.put(entry.getKey(), buckets);
            }
        }

        return new Aggregations(facets, priceStats);
    }

    /**
     * Terms aggregations come back as one of three concrete types depending on the
     * mapped field type — string, long or double — and a boolean field aggregates
     * as long. Rather than assume, each variant is handled and the key normalised
     * back to a string the UI can use verbatim.
     */
    private List<FacetBucket> toBuckets(Aggregate aggregate) {
        if (aggregate.isSterms()) {
            return map(aggregate.sterms().buckets().array(),
                    bucket -> new FacetBucket(
                            bucket.key().stringValue(),
                            humanise(bucket.key().stringValue()),
                            bucket.docCount()));
        }
        if (aggregate.isLterms()) {
            return map(aggregate.lterms().buckets().array(),
                    bucket -> {
                        String key = bucket.keyAsString() != null
                                ? bucket.keyAsString()
                                : String.valueOf(bucket.key());
                        return new FacetBucket(key, humanise(key), bucket.docCount());
                    });
        }
        if (aggregate.isDterms()) {
            return map(aggregate.dterms().buckets().array(),
                    bucket -> {
                        String key = bucket.keyAsString() != null
                                ? bucket.keyAsString()
                                : String.valueOf((long) bucket.key());
                        return new FacetBucket(key, humanise(key), bucket.docCount());
                    });
        }
        return List.of();
    }

    private static <T> List<FacetBucket> map(List<T> buckets, Function<T, FacetBucket> mapper) {
        List<FacetBucket> mapped = new ArrayList<>(buckets.size());
        buckets.forEach(bucket -> mapped.add(mapper.apply(bucket)));
        return mapped;
    }

    private static PriceStats toPriceStats(StatsAggregate stats) {
        if (stats.count() == 0) {
            return null;
        }
        return new PriceStats(
                scaled(stats.min()),
                scaled(stats.max()),
                scaled(stats.avg()));
    }

    private static BigDecimal scaled(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code SEMI_FURNISHED} to {@code Semi furnished}, so the UI needs no lookup table. */
    private static String humanise(String key) {
        if (key == null || key.isBlank() || !key.equals(key.toUpperCase())) {
            return key;
        }
        String spaced = key.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
