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

import com.nivas.analytics.ViewCounterService;
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
import com.nivas.catalog.dto.PropertyDtos.PropertyDetailResponse;
import com.nivas.catalog.dto.PropertyDtos.UpsertPropertyRequest;
import com.nivas.search.PropertyDiscoveryService;
import com.nivas.search.document.PropertyDocument;
import com.nivas.search.dto.SearchDtos.PropertySummary;
import com.nivas.security.AppUserPrincipal;
import com.nivas.support.IntegrationTest;

/**
 * The discovery surfaces, against real Elasticsearch and Redis.
 *
 * <p>Every one of these covers something that was silently broken when first
 * written, and none of it was visible without running the stack: a completion
 * field with a context refuses queries that omit one, prefixes only match the
 * start of an indexed input, and external versioning made a full reindex
 * impossible.
 */
@IntegrationTest
class DiscoveryIT {

    private static final Duration PIPELINE_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private PropertyDiscoveryService discoveryService;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LocalityRepository localityRepository;

    @Autowired
    private ViewCounterService viewCounter;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    private AppUserPrincipal owner;
    private Locality koramangala;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(new User(
                "discovery-" + UUID.randomUUID() + "@nivas.test", "hash", "Owner", null, Role.OWNER));
        owner = AppUserPrincipal.from(user);
        koramangala = localityRepository.findBySlug("bengaluru-koramangala").orElseThrow();
    }

    @Test
    @DisplayName("type-ahead matches a locality prefix, not just the start of a title")
    void suggestsOnLocalityPrefix() {
        publish("Sunlit 3 BHK near the metro", new BigDecimal("48000"), (short) 3);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();

            // "kora" appears nowhere at the start of the title. It only matches
            // because the indexed inputs lead with the locality — which is what
            // people actually type.
            var suggestions = discoveryService.suggest("kora", null).suggestions();

            assertThat(suggestions).isNotEmpty();
            assertThat(suggestions).extracting(s -> s.text().toLowerCase())
                    .anyMatch(text -> text.startsWith("koramangala"));
        });
    }

    @Test
    @DisplayName("type-ahead works without a city, despite the mapping's context")
    void suggestsWithoutACity() {
        publish("Quiet 2 BHK with covered parking", new BigDecimal("39000"), (short) 2);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();

            // A context-enabled completion field fails the shard outright when a
            // query supplies no context. The unscoped path has to pass every
            // known city rather than none.
            assertThat(discoveryService.suggest("kora", null).suggestions())
                    .as("an unscoped suggestion must not fail or come back empty")
                    .isNotEmpty();
        });
    }

    @Test
    @DisplayName("the city context scopes suggestions")
    void cityContextScopesSuggestions() {
        publish("Bright 2 BHK near the park", new BigDecimal("41000"), (short) 2);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();
            assertThat(discoveryService.suggest("kora", "Bengaluru").suggestions()).isNotEmpty();
        });

        assertThat(discoveryService.suggest("kora", "Pune").suggestions())
                .as("a Bengaluru locality must not surface when scoped to Pune")
                .isEmpty();
    }

    @Test
    @DisplayName("trending ranks by the live Redis counter and hydrates from the index")
    void trendingReflectsRecordedViews() {
        PropertyDetailResponse listing = publish("Roomy 3 BHK on a high floor",
                new BigDecimal("62000"), (short) 3);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();
            assertThat(elasticsearch.get(listing.id().toString(), PropertyDocument.class)).isNotNull();
        });

        // Distinct viewers: the counter de-duplicates per viewer for 30 minutes,
        // so repeating one key would register a single view.
        for (int viewer = 0; viewer < 4; viewer++) {
            viewCounter.recordView(listing.id(), "viewer-" + viewer);
        }

        List<PropertySummary> trending = discoveryService.trending(10);

        assertThat(trending).extracting(summary -> UUID.fromString(summary.id()))
                .contains(listing.id());
    }

    @Test
    @DisplayName("similar listings exclude the source and keep to its listing type")
    void similarListingsAreUsable() {
        PropertyDetailResponse source = publish("Sunlit 3 BHK near the metro",
                new BigDecimal("55000"), (short) 3);
        publish("Airy 3 BHK near the metro", new BigDecimal("57000"), (short) 3);
        publish("Quiet 3 BHK close to the park", new BigDecimal("59000"), (short) 3);

        await().atMost(PIPELINE_TIMEOUT).untilAsserted(() -> {
            elasticsearch.indexOps(PropertyDocument.class).refresh();

            List<PropertySummary> similar = discoveryService.similarTo(source.id());

            assertThat(similar).isNotEmpty();
            assertThat(similar).extracting(PropertySummary::id)
                    .as("a listing is its own best match; it must be excluded")
                    .doesNotContain(source.id().toString());
            assertThat(similar).extracting(PropertySummary::listingType)
                    .containsOnly(source.listingType().name());
        });
    }

    @Test
    @DisplayName("similar listings for an unknown id return empty rather than failing")
    void similarForUnknownIdIsEmpty() {
        assertThat(discoveryService.similarTo(UUID.randomUUID())).isEmpty();
    }

    // -- fixtures ------------------------------------------------------------

    private PropertyDetailResponse publish(String title, BigDecimal price, short bedrooms) {
        PropertyDetailResponse created = propertyService.create(
                new UpsertPropertyRequest(
                        title + " in Koramangala",
                        "A well-kept home close to shops, transport and parks.",
                        ListingType.RENT, PropertyType.APARTMENT,
                        price, new BigDecimal("2500"), price.multiply(BigDecimal.valueOf(2)),
                        true, null,
                        bedrooms, (short) 2, (short) 1, Furnishing.SEMI_FURNISHED,
                        1100, 1350, (short) 4, (short) 10, (short) 6,
                        koramangala.getId(), "80 Feet Road", "Bengaluru", "Karnataka", "560095",
                        12.9352, 77.6245, null,
                        Set.of(Amenity.PARKING, Amenity.LIFT),
                        List.of(new ImagePayload("https://cdn.nivas.test/1.jpg", null, 1600, 900),
                                new ImagePayload("https://cdn.nivas.test/2.jpg", null, 1600, 900),
                                new ImagePayload("https://cdn.nivas.test/3.jpg", null, 1600, 900))),
                owner);

        propertyService.changeStatus(created.id(), PropertyStatus.PENDING_REVIEW, owner);
        return propertyService.changeStatus(created.id(), PropertyStatus.ACTIVE, owner);
    }
}
