package com.nivas.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import com.nivas.auth.UserRepository;
import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;
import com.nivas.catalog.LocalityRepository;
import com.nivas.catalog.PropertyService;
import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.Furnishing;
import com.nivas.catalog.domain.ListingType;
import com.nivas.catalog.domain.Locality;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.catalog.domain.PropertyType;
import com.nivas.catalog.dto.PropertyDtos.ImagePayload;
import com.nivas.catalog.dto.PropertyDtos.ImageResponse;
import com.nivas.catalog.dto.PropertyDtos.PropertyDetailResponse;
import com.nivas.catalog.dto.PropertyDtos.UpsertPropertyRequest;
import com.nivas.outbox.OutboxEventRepository;
import com.nivas.outbox.OutboxStatus;
import com.nivas.outbox.PropertyChangedEvent;
import com.nivas.search.PropertySearchService;
import com.nivas.search.SearchRequestBuilder;
import com.nivas.search.document.PropertyDocument;
import com.nivas.search.dto.SearchDtos.SearchResponse;
import com.nivas.security.AppUserPrincipal;
import com.nivas.support.IntegrationTest;

/**
 * The end-to-end path this whole architecture exists to serve:
 * write to Postgres, event through the outbox, project into Elasticsearch,
 * find it by search.
 *
 * <p>Every hop is real. The assertions use {@code await} rather than sleeps
 * because the pipeline is asynchronous by design, and a fixed sleep is either
 * flaky or slow.
 */
@IntegrationTest
class PropertyPipelineIT {

    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private PropertySearchService searchService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocalityRepository localityRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    private AppUserPrincipal owner;
    private Locality koramangala;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(new User(
                "owner-" + UUID.randomUUID() + "@nivas.test", "hash", "Test Owner", null, Role.OWNER));
        owner = AppUserPrincipal.from(user);

        koramangala = localityRepository.findBySlug("bengaluru-koramangala").orElseThrow(
                () -> new IllegalStateException("V2 seed migration did not run"));

        // Deliberately does NOT create the index. It used to, and that is exactly
        // why this suite passed while the real application was broken: nothing in
        // production created the index, Elasticsearch auto-created it from the
        // first document with an inferred mapping, and every analyzer and the
        // geo_point were silently lost. SearchIndexInitializer now owns this, and
        // the assertion below holds it to that.
        assertThat(elasticsearch.indexOps(PropertyDocument.class).exists())
                .as("SearchIndexInitializer should have created the index on startup")
                .isTrue();
    }

    @Test
    @DisplayName("fetching a listing does not multiply its collections")
    void collectionsAreNotCartesianProducts() {
        // Three photos and three amenities. Joining both collections in one
        // query returns 3 x 3 = 9 duplicated images, which inflates every detail
        // payload and corrupts the imageCount the relevance boost reads.
        PropertyDetailResponse created = propertyService.create(
                listingRequest("Listing with a known photo count", new BigDecimal("40000")), owner);

        PropertyDetailResponse fetched = propertyService.getForOwner(created.id(), owner);

        assertThat(fetched.images())
                .as("one entry per row in property_images, not |images| x |amenities|")
                .hasSize(3);
        assertThat(fetched.images()).extracting(ImageResponse::position)
                .as("positions must be dense and distinct")
                .containsExactly(0, 1, 2);
        assertThat(fetched.amenities()).hasSize(3);
    }

    @Test
    @DisplayName("the index is created with the real mapping, not inferred by Elasticsearch")
    void indexUsesTheAuthoredMapping() {
        var mapping = elasticsearch.indexOps(PropertyDocument.class).getMapping();

        assertThat(mapping).isNotNull();
        @SuppressWarnings("unchecked")
        var fields = (java.util.Map<String, Object>) mapping.get("properties");

        // An auto-created index infers location as two floats, which silently
        // breaks every geo query while search still appears to work.
        assertThat(fields).containsKey("location");
        @SuppressWarnings("unchecked")
        var location = (java.util.Map<String, Object>) fields.get("location");
        assertThat(location.get("type"))
                .as("must be geo_point — inferred mappings make this an object of floats")
                .isEqualTo("geo_point");

        @SuppressWarnings("unchecked")
        var suggest = (java.util.Map<String, Object>) fields.get("suggest");
        assertThat(suggest.get("type")).isEqualTo("completion");

        assertThat(mapping.get("dynamic"))
                .as("strict is what stops an unmapped field being indexed with a guessed type")
                .isEqualTo("strict");
    }

    @Test
    @DisplayName("a published listing becomes searchable through the outbox")
    void publishedListingBecomesSearchable() {
        PropertyDetailResponse created = propertyService.create(listingRequest("Bright 2 BHK in Koramangala",
                new BigDecimal("42000")), owner);

        assertThat(created.status()).isEqualTo(PropertyStatus.DRAFT);

        // A draft must never reach the index. Scoped to this listing, not a
        // global count: these tests deliberately share a context and do not roll
        // back — the relay polls committed rows — so earlier tests have left
        // their own events behind.
        assertThat(outboxRepository.countByAggregateTypeAndAggregateId(
                PropertyChangedEvent.AGGREGATE_TYPE, created.id().toString()))
                .as("creating a draft must not emit an outbox event")
                .isZero();

        propertyService.changeStatus(created.id(), PropertyStatus.PENDING_REVIEW, owner);
        propertyService.changeStatus(created.id(), PropertyStatus.ACTIVE, owner);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();
            SearchResponse response = searchService.search(
                    SearchRequestBuilder.request().q("koramangala").build());

            assertThat(response.results().content())
                    .extracting(summary -> UUID.fromString(summary.id()))
                    .contains(created.id());
        });

        // The relay should have drained everything it produced.
        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() ->
                assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isZero());
    }

    @Test
    @DisplayName("archiving a listing removes it from the index")
    void archivedListingLeavesTheIndex() {
        PropertyDetailResponse created = publishListing("Sunny 3 BHK near the park", new BigDecimal("58000"));

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();
            assertThat(elasticsearch.get(created.id().toString(), PropertyDocument.class)).isNotNull();
        });

        propertyService.archive(created.id(), owner);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();
            assertThat(elasticsearch.get(created.id().toString(), PropertyDocument.class))
                    .as("an archived listing must not remain searchable")
                    .isNull();
        });
    }

    @Test
    @DisplayName("filters narrow results and facets reflect what is left")
    void filtersAndFacets() {
        publishListing("Compact 1 BHK studio", new BigDecimal("18000"), (short) 1);
        publishListing("Roomy 3 BHK family home", new BigDecimal("75000"), (short) 3);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();

            SearchResponse cheap = searchService.search(
                    SearchRequestBuilder.request().price(null, "20000").build());

            assertThat(cheap.results().content())
                    .as("a 75k listing must not appear under a 20k ceiling")
                    .allSatisfy(summary ->
                            assertThat(summary.price()).isLessThanOrEqualTo(new BigDecimal("20000")));

            assertThat(cheap.facets()).containsKey("propertyType");
            assertThat(cheap.priceStats()).isNotNull();
        });
    }

    @Test
    @DisplayName("a geo search only returns listings inside the radius")
    void geoSearchRespectsRadius() {
        publishListing("Flat right by Koramangala", new BigDecimal("40000"));

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();

            SearchResponse near = searchService.search(SearchRequestBuilder.request()
                    .point(12.9352, 77.6245, 3.0)
                    .build());
            assertThat(near.results().totalElements()).isPositive();

            // Mumbai, roughly 850km away.
            SearchResponse far = searchService.search(SearchRequestBuilder.request()
                    .point(19.0596, 72.8295, 3.0)
                    .build());
            assertThat(far.results().totalElements())
                    .as("a Bengaluru listing must not match a 3km radius around Mumbai")
                    .isZero();
        });
    }

    // -- fixtures ------------------------------------------------------------

    private PropertyDetailResponse publishListing(String title, BigDecimal price) {
        return publishListing(title, price, (short) 2);
    }

    private PropertyDetailResponse publishListing(String title, BigDecimal price, short bedrooms) {
        PropertyDetailResponse created = propertyService.create(listingRequest(title, price, bedrooms), owner);
        propertyService.changeStatus(created.id(), PropertyStatus.PENDING_REVIEW, owner);
        return propertyService.changeStatus(created.id(), PropertyStatus.ACTIVE, owner);
    }

    private UpsertPropertyRequest listingRequest(String title, BigDecimal price) {
        return listingRequest(title, price, (short) 2);
    }

    private UpsertPropertyRequest listingRequest(String title, BigDecimal price, short bedrooms) {
        return new UpsertPropertyRequest(
                title,
                "A well-kept home close to shops, transport and parks.",
                ListingType.RENT,
                PropertyType.APARTMENT,
                price,
                new BigDecimal("2500"),
                price.multiply(BigDecimal.valueOf(2)),
                true,
                null,
                bedrooms, (short) 2, (short) 1,
                Furnishing.SEMI_FURNISHED,
                1100, 1350,
                (short) 4, (short) 10, (short) 6,
                koramangala.getId(),
                "80 Feet Road",
                "Bengaluru",
                "Karnataka",
                "560095",
                12.9352,
                77.6245,
                null,
                Set.of(Amenity.PARKING, Amenity.LIFT, Amenity.POWER_BACKUP),
                List.of(
                        new ImagePayload("https://cdn.nivas.test/1.jpg", "Living room", 1600, 900),
                        new ImagePayload("https://cdn.nivas.test/2.jpg", "Kitchen", 1600, 900),
                        new ImagePayload("https://cdn.nivas.test/3.jpg", "Balcony", 1600, 900)));
    }
}
