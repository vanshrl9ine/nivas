package com.nivas.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SortOption;

import co.elastic.clients.elasticsearch._types.DistanceUnit;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.GeoDistanceType;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;

/**
 * Turns a {@link SearchRequest} into an Elasticsearch query.
 *
 * <p>The single most important structural decision here is the split between
 * <em>must</em> and <em>filter</em>. Only the free-text part contributes to the
 * score; every structured constraint (price, bedrooms, city, geo) goes in the
 * filter clause, where it is not scored and — crucially — is cacheable by
 * Elasticsearch in the node query cache. Putting a price range in {@code must}
 * would make an exact-price match "more relevant", which is meaningless, and
 * would forfeit that caching.
 */
@Component
public class PropertyQueryBuilder {

    /**
     * Field boosts for the text query.
     *
     * <p>Title carries the most signal, and the locality name close behind — for
     * this product "koramangala" is a location intent far more often than it is a
     * word someone wants matched inside a description. Description is included
     * with no boost so it can break ties, not drive them.
     */
    private static final List<String> TEXT_FIELDS = List.of(
            "title^4",
            "localityName.text^3",
            "city.text^2",
            "addressLine^1.5",
            "description");

    private static final int MAX_FACET_BUCKETS = 20;

    /** Bedrooms at or above this are bucketed together — "4 BHK" means "4+". */
    private static final short BEDROOM_CAP = 4;

    public Query build(SearchRequest request) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (request.hasText()) {
            bool.must(textQuery(request.q()));
        } else {
            bool.must(Query.of(q -> q.matchAll(m -> m)));
        }

        filters(request).forEach(bool::filter);

        Query base = Query.of(q -> q.bool(bool.build()));

        // Without query text everything ties on score, so the boosts below would
        // only add cost for no ordering benefit.
        return request.hasText() ? applyBoosts(base) : base;
    }

    /**
     * Multi-field text match.
     *
     * <p>{@code best_fields} rather than {@code cross_fields}: a listing whose
     * title alone matches "2 bhk indiranagar" is a better result than one that
     * matches "2 bhk" in the title and "indiranagar" only in a rambling
     * description. Fuzziness is {@code AUTO} so short tokens are matched exactly
     * while longer, more misspellable words get one or two edits of slack.
     */
    private Query textQuery(String text) {
        return Query.of(q -> q.multiMatch(m -> m
                .query(text)
                .fields(TEXT_FIELDS)
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")
                .prefixLength(2)
                .minimumShouldMatch("2<75%")
                .tieBreaker(0.3)));
    }

    /**
     * Boosts applied on top of textual relevance.
     *
     * <p>Multiplicative ({@code boostMode = multiply}) so these adjust the text
     * score rather than replacing it — a poor text match should not float to the
     * top merely because it is verified and recent.
     */
    private Query applyBoosts(Query base) {
        List<FunctionScore> functions = List.of(
                // Verified listings are the product's quality signal.
                FunctionScore.of(fn -> fn
                        .filter(f -> f.term(t -> t.field("verified").value(true)))
                        .weight(1.35)),

                // Freshness, decaying over roughly a month, with a three-day grace
                // period so today's and Tuesday's listings are not separated.
                // A listing that has been up for a year is usually already gone.
                FunctionScore.of(fn -> fn.gauss(g -> g.date(d -> d
                        .field("publishedAt")
                        .placement(p -> p
                                .origin("now")
                                .scale(Time.of(t -> t.time("30d")))
                                .offset(Time.of(t -> t.time("3d")))
                                .decay(0.6))))),

                // Popularity, heavily damped. log1p keeps a listing with 100k views
                // from burying everything else, and the counter is only eventually
                // consistent anyway.
                FunctionScore.of(fn -> fn.fieldValueFactor(fv -> fv
                        .field("viewCount")
                        .modifier(FieldValueFactorModifier.Log1p)
                        .factor(0.1)
                        .missing(0.0))),

                // Listings with photos convert; listings without barely do.
                FunctionScore.of(fn -> fn
                        .filter(f -> f.range(r -> r.number(n -> n.field("imageCount").gte(3.0))))
                        .weight(1.15)));

        return Query.of(q -> q.functionScore(fs -> fs
                .query(base)
                .functions(functions)
                .scoreMode(FunctionScoreMode.Multiply)
                .boostMode(FunctionBoostMode.Multiply)
                // Caps the combined boost so signals can reorder near-equal text
                // matches without ever overriding a clearly better one.
                .maxBoost(3.0)));
    }

    /** Non-scoring constraints. Order is irrelevant to correctness but grouped for readability. */
    private List<Query> filters(SearchRequest request) {
        List<Query> filters = new ArrayList<>();

        // Non-negotiable: the index may briefly contain a listing that has just
        // been taken down, and it must never surface.
        filters.add(termQuery("status", PropertyStatus.ACTIVE.name()));

        if (request.listingType() != null) {
            filters.add(termQuery("listingType", request.listingType().name()));
        }
        if (request.city() != null && !request.city().isBlank()) {
            filters.add(termQuery("city", request.city().trim().toLowerCase()));
        }
        if (notEmpty(request.localitySlugs())) {
            filters.add(termsQuery("localitySlug", request.localitySlugs()));
        }
        if (notEmpty(request.propertyTypes())) {
            filters.add(termsQuery("propertyType", request.propertyTypes().stream().map(Enum::name).toList()));
        }
        if (notEmpty(request.furnishing())) {
            filters.add(termsQuery("furnishing", request.furnishing().stream().map(Enum::name).toList()));
        }
        if (Boolean.TRUE.equals(request.verifiedOnly())) {
            filters.add(Query.of(q -> q.term(t -> t.field("verified").value(true))));
        }

        if (request.minPrice() != null || request.maxPrice() != null) {
            filters.add(numberRange("price",
                    request.minPrice() == null ? null : request.minPrice().doubleValue(),
                    request.maxPrice() == null ? null : request.maxPrice().doubleValue()));
        }
        if (request.minAreaSqft() != null || request.maxAreaSqft() != null) {
            filters.add(numberRange("carpetAreaSqft",
                    request.minAreaSqft() == null ? null : request.minAreaSqft().doubleValue(),
                    request.maxAreaSqft() == null ? null : request.maxAreaSqft().doubleValue()));
        }

        if (notEmpty(request.bedrooms())) {
            filters.add(bedroomFilter(request.bedrooms()));
        }

        // AND semantics: one filter clause per amenity. A single terms query would
        // mean "has any of these", which is not what a user ticking three boxes wants.
        if (notEmpty(request.amenities())) {
            request.amenities().forEach(amenity -> filters.add(termQuery("amenities", amenity.name())));
        }

        // A bounding box wins when both are present: it is what the user is
        // literally looking at on the map.
        if (request.hasBoundingBox()) {
            filters.add(boundingBoxFilter(request));
        } else if (request.hasGeoPoint()) {
            filters.add(geoDistanceFilter(request));
        }

        return filters;
    }

    /**
     * "4 BHK" is understood as "4 or more", so the top bucket becomes an open
     * range while the others stay exact.
     */
    private Query bedroomFilter(List<Short> bedrooms) {
        boolean wantsCapped = bedrooms.stream().anyMatch(b -> b != null && b >= BEDROOM_CAP);
        List<String> exact = bedrooms.stream()
                .filter(b -> b != null && b < BEDROOM_CAP)
                .map(String::valueOf)
                .toList();

        return Query.of(q -> q.bool(b -> {
            if (!exact.isEmpty()) {
                b.should(termsQuery("bedrooms", exact));
            }
            if (wantsCapped) {
                b.should(numberRange("bedrooms", (double) BEDROOM_CAP, null));
            }
            return b.minimumShouldMatch("1");
        }));
    }

    private Query geoDistanceFilter(SearchRequest request) {
        return Query.of(q -> q.geoDistance(g -> g
                .field("location")
                .distance(request.radiusKm() + "km")
                // Arc is more accurate than the plane approximation and the cost
                // difference is irrelevant at this scale.
                .distanceType(GeoDistanceType.Arc)
                .location(l -> l.latlon(ll -> ll.lat(request.lat()).lon(request.lon())))));
    }

    private Query boundingBoxFilter(SearchRequest request) {
        return Query.of(q -> q.geoBoundingBox(g -> g
                .field("location")
                .boundingBox(bb -> bb.tlbr(t -> t
                        .topLeft(tl -> tl.latlon(ll -> ll.lat(request.topLeftLat()).lon(request.topLeftLon())))
                        .bottomRight(br -> br.latlon(
                                ll -> ll.lat(request.bottomRightLat()).lon(request.bottomRightLon())))))));
    }

    // -- sorting -------------------------------------------------------------

    public List<SortOptions> sortOptions(SearchRequest request) {
        SortOption sort = request.effectiveSort();
        List<SortOptions> options = new ArrayList<>();

        switch (sort) {
            case PRICE_ASC -> options.add(fieldSort("price", SortOrder.Asc));
            case PRICE_DESC -> options.add(fieldSort("price", SortOrder.Desc));
            case AREA_DESC -> options.add(fieldSort("carpetAreaSqft", SortOrder.Desc));
            case NEWEST -> options.add(fieldSort("publishedAt", SortOrder.Desc));
            case DISTANCE -> {
                if (request.hasGeoPoint()) {
                    options.add(SortOptions.of(s -> s.geoDistance(g -> g
                            .field("location")
                            .location(l -> l.latlon(ll -> ll.lat(request.lat()).lon(request.lon())))
                            .order(SortOrder.Asc)
                            .unit(DistanceUnit.Kilometers)
                            .distanceType(GeoDistanceType.Arc))));
                } else {
                    options.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
                }
            }
            case RELEVANCE -> options.add(SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));
        }

        // Deterministic tiebreak. Without it, two listings with an identical sort
        // value can swap places between requests and appear twice — or not at
        // all — as the user pages through results.
        options.add(fieldSort("id", SortOrder.Asc));
        return options;
    }

    private static SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    // -- aggregations --------------------------------------------------------

    /**
     * Facet counts, returned alongside every search so the UI can show how many
     * results each refinement would leave.
     *
     * <p>These are computed over the filtered result set, which means a facet
     * reflects the other filters already applied. That is the behaviour users
     * expect from a refinement panel, and it is why they live on the main query
     * rather than in a separate unfiltered request.
     */
    public Map<String, Aggregation> aggregations() {
        Map<String, Aggregation> aggregations = new LinkedHashMap<>();

        aggregations.put("propertyType", termsAgg("propertyType"));
        aggregations.put("bedrooms", termsAgg("bedrooms"));
        aggregations.put("furnishing", termsAgg("furnishing"));
        aggregations.put("localityName", termsAgg("localityName"));
        aggregations.put("amenities", termsAgg("amenities"));
        aggregations.put("verified", termsAgg("verified"));

        // Powers the price histogram in the filter panel.
        aggregations.put("priceStats", Aggregation.of(a -> a.stats(s -> s.field("price"))));

        return aggregations;
    }

    private static Aggregation termsAgg(String field) {
        return Aggregation.of(a -> a.terms(t -> t.field(field).size(MAX_FACET_BUCKETS)));
    }

    // -- small helpers -------------------------------------------------------

    private static Query termQuery(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    private static Query termsQuery(String field, List<String> values) {
        List<FieldValue> fieldValues = values.stream().map(FieldValue::of).toList();
        return Query.of(q -> q.terms(t -> t.field(field).terms(tv -> tv.value(fieldValues))));
    }

    private static Query numberRange(String field, Double gte, Double lte) {
        return Query.of(q -> q.range(r -> r.number(n -> {
            n.field(field);
            if (gte != null) {
                n.gte(gte);
            }
            if (lte != null) {
                n.lte(lte);
            }
            return n;
        })));
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
