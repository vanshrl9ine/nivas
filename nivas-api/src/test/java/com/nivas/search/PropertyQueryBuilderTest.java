package com.nivas.search;

import static com.nivas.search.SearchRequestBuilder.request;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.ListingType;
import com.nivas.catalog.domain.PropertyType;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SortOption;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.JsonpSerializable;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import jakarta.json.stream.JsonGenerator;

/**
 * Verifies the generated Elasticsearch query by serialising it to JSON.
 *
 * <p>No cluster is needed, which is the point: query construction is where search
 * bugs actually live, and asserting on the emitted DSL catches them in
 * milliseconds rather than behind a container boot. It also pins the decisions
 * that are easy to undo by accident — filters staying out of the scoring clause,
 * amenities being AND rather than OR, the status guard always being present.
 */
class PropertyQueryBuilderTest {

    private static final JsonpMapper JSONP = new JacksonJsonpMapper();
    private static final ObjectMapper JACKSON = new ObjectMapper();

    private PropertyQueryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PropertyQueryBuilder();
    }

    private static JsonNode toJson(JsonpSerializable serializable) {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = JSONP.jsonProvider().createGenerator(writer)) {
            serializable.serialize(generator, JSONP);
        }
        try {
            return JACKSON.readTree(writer.toString());
        } catch (Exception ex) {
            throw new AssertionError("Query did not serialise to valid JSON: " + writer, ex);
        }
    }

    /** Unwraps the function_score wrapper that text queries are nested inside. */
    private static JsonNode boolNode(JsonNode query) {
        return query.has("function_score")
                ? query.path("function_score").path("query").path("bool")
                : query.path("bool");
    }

    private JsonNode filtersOf(SearchRequest request) {
        return boolNode(toJson(builder.build(request))).path("filter");
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        @Test
        @DisplayName("every query filters to ACTIVE listings")
        void alwaysFiltersToActive() {
            JsonNode filters = filtersOf(request().build());

            assertThat(filters).isNotEmpty();
            assertThat(filters.toString()).contains("\"status\"").contains("ACTIVE");
        }

        @Test
        @DisplayName("with no text the query is a plain bool, not a function_score")
        void noBoostsWithoutText() {
            JsonNode json = toJson(builder.build(request().build()));

            assertThat(json.has("function_score"))
                    .as("scoring functions are pointless when every document ties")
                    .isFalse();
            assertThat(json.has("bool")).isTrue();
        }

        @Test
        @DisplayName("free text produces a multi_match inside must, wrapped in boosts")
        void textProducesMultiMatch() {
            JsonNode json = toJson(builder.build(request().q("2 bhk koramangala").build()));

            assertThat(json.has("function_score")).isTrue();

            String must = boolNode(json).path("must").toString();
            assertThat(must).contains("multi_match").contains("title^4");
        }

        @Test
        @DisplayName("structured constraints go in filter, never in must")
        void structuredConstraintsStayUnscored() {
            SearchRequest req = request()
                    .q("koramangala")
                    .city("Bengaluru")
                    .listingType(ListingType.RENT)
                    .propertyTypes(PropertyType.APARTMENT)
                    .price("20000", "50000")
                    .build();

            JsonNode bool = boolNode(toJson(builder.build(req)));

            assertThat(bool.path("must").toString())
                    .as("price must not influence relevance")
                    .doesNotContain("\"price\"")
                    .doesNotContain("listingType");

            assertThat(bool.path("filter").toString())
                    .contains("\"price\"").contains("listingType").contains("propertyType");
        }

        @Test
        @DisplayName("boosts are multiplicative and capped")
        void boostsAreMultiplicativeAndCapped() {
            String json = toJson(builder.build(request().q("villa").build()))
                    .path("function_score").toString();

            assertThat(json).contains("\"boost_mode\":\"multiply\"");
            assertThat(json).contains("max_boost");
            assertThat(json).contains("verified");
        }
    }

    @Nested
    @DisplayName("filters")
    class Filters {

        @Test
        @DisplayName("multiple amenities are ANDed as separate clauses")
        void amenitiesAreAnded() {
            JsonNode filters = filtersOf(request()
                    .amenities(Amenity.PARKING, Amenity.LIFT, Amenity.GYM)
                    .build());

            assertThat(filters.findValues("amenities"))
                    .as("three ticked amenities must all be required, not any-of")
                    .hasSize(3);
        }

        @Test
        @DisplayName("city is lowercased to match the keyword normaliser")
        void cityIsLowercased() {
            assertThat(filtersOf(request().city("Bengaluru").build()).toString())
                    .contains("bengaluru")
                    .doesNotContain("Bengaluru");
        }

        @Test
        @DisplayName("the top bedroom bucket becomes an open-ended range")
        void topBedroomBucketIsOpenEnded() {
            String filters = filtersOf(request().bedrooms((short) 2, (short) 4).build()).toString();

            assertThat(filters).contains("bedrooms");
            assertThat(filters)
                    .as("4 BHK should mean 4 or more, so a range clause is expected")
                    .contains("gte");
        }

        @Test
        @DisplayName("a point and radius produce a geo_distance filter")
        void geoDistanceFilter() {
            assertThat(filtersOf(request().point(12.9352, 77.6245, 5.0).build()).toString())
                    .contains("geo_distance")
                    .contains("5.0km");
        }

        @Test
        @DisplayName("a bounding box wins over a point when both are supplied")
        void boundingBoxWinsOverPoint() {
            String filters = filtersOf(request()
                    .point(12.9352, 77.6245, 5.0)
                    .boundingBox(13.1, 77.5, 12.8, 77.8)
                    .build()).toString();

            assertThat(filters).contains("geo_bounding_box");
            assertThat(filters)
                    .as("the viewport the user is looking at should take precedence")
                    .doesNotContain("geo_distance");
        }

        @Test
        @DisplayName("verifiedOnly adds a term filter")
        void verifiedOnlyFilter() {
            assertThat(filtersOf(request().verifiedOnly(true).build()).toString())
                    .contains("verified");
        }
    }

    @Nested
    @DisplayName("sorting")
    class Sorting {

        @Test
        @DisplayName("a deterministic tiebreak is always appended")
        void tiebreakIsAlwaysPresent() {
            for (SortOption option : SortOption.values()) {
                List<SortOptions> sorts = builder.sortOptions(request().sort(option).build());

                assertThat(sorts).hasSizeGreaterThanOrEqualTo(2);
                assertThat(toJson(sorts.get(sorts.size() - 1)).toString())
                        .as("%s must end with an id tiebreak so paging is stable", option)
                        .contains("\"id\"");
            }
        }

        @Test
        @DisplayName("distance sort falls back to relevance without a point")
        void distanceWithoutPointFallsBack() {
            String json = toJson(builder.sortOptions(request().sort(SortOption.DISTANCE).build())
                    .get(0)).toString();

            assertThat(json).contains("_score");
            assertThat(json).doesNotContain("_geo_distance");
        }

        @Test
        @DisplayName("distance sort asks Elasticsearch for kilometres")
        void distanceSortUsesKilometres() {
            SearchRequest req = request()
                    .point(12.9352, 77.6245, 5.0)
                    .sort(SortOption.DISTANCE)
                    .build();

            assertThat(toJson(builder.sortOptions(req).get(0)).toString())
                    .contains("_geo_distance")
                    .contains("km");
        }

        @Test
        @DisplayName("price sort maps to the price field ascending")
        void priceSort() {
            assertThat(toJson(builder.sortOptions(request().sort(SortOption.PRICE_ASC).build()).get(0))
                    .toString())
                    .contains("\"price\"")
                    .contains("asc");
        }

        @Test
        @DisplayName("default sort is newest without text and relevance with it")
        void defaultSortDependsOnText() {
            assertThat(request().build().effectiveSort()).isEqualTo(SortOption.NEWEST);
            assertThat(request().q("villa").build().effectiveSort()).isEqualTo(SortOption.RELEVANCE);
        }
    }

    @Test
    @DisplayName("facets cover every filterable dimension plus price stats")
    void aggregationsCoverFilters() {
        assertThat(builder.aggregations()).containsKeys(
                "propertyType", "bedrooms", "furnishing", "localityName", "amenities", "verified",
                "priceStats");
    }
}
