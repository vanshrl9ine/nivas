package com.nivas.home;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.home.dto.HomeDtos.HomePage;
import com.nivas.home.dto.HomeDtos.HomeSection;
import com.nivas.placement.PlacementService;
import com.nivas.placement.domain.Surface;
import com.nivas.search.PropertyDiscoveryService;
import com.nivas.search.PropertySummaryMapper;
import com.nivas.search.document.PropertyDocument;
import com.nivas.search.dto.SearchDtos.PropertySummary;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

/**
 * Builds the national and per-city home pages.
 *
 * <p>A home page is a list of sections, assembled from three sources: sold
 * placement inventory, live popularity, and plain recency. The interesting rule
 * is what happens when one advertiser has bought the whole city — the page
 * collapses to their takeover, which is precisely what they paid for.
 */
@Service
public class HomeService {

    private static final int RAIL_SIZE = 8;

    private final PlacementService placementService;
    private final PropertyDiscoveryService discoveryService;
    private final ElasticsearchOperations operations;

    public HomeService(PlacementService placementService, PropertyDiscoveryService discoveryService,
            ElasticsearchOperations operations) {
        this.placementService = placementService;
        this.discoveryService = discoveryService;
        this.operations = operations;
    }

    /**
     * The all-India page.
     *
     * <p>No takeover concept here: the national hero is a rotating pool of three
     * slots rather than something one advertiser can own outright. That is a
     * deliberate commercial decision, expressed as capacity rather than as code.
     */
    public HomePage national() {
        LocalDate today = LocalDate.now();
        List<HomeSection> sections = new ArrayList<>();

        List<PropertySummary> hero = byIds(
                placementService.livePropertyIds(Surface.NATIONAL_HOME_HERO, null, today));
        if (!hero.isEmpty()) {
            sections.add(new HomeSection("hero", "Featured across India", "PROMOTED", hero));
        }

        List<PropertySummary> trending = discoveryService.trending(RAIL_SIZE);
        if (!trending.isEmpty()) {
            sections.add(new HomeSection("trending", "Trending today", "ORGANIC", trending));
        }

        sections.add(new HomeSection("recent", "Recently listed", "ORGANIC",
                recentlyListed(null, RAIL_SIZE)));

        return new HomePage(null, false, sections);
    }

    /**
     * A city's page.
     *
     * <p>When an audience maximizer holds the hero, the page becomes that one
     * listing plus a short "also in this city" rail. Nothing checks a takeover
     * flag — {@link PlacementService#isTakenOver} derives it from whether a
     * single listing holds every hero slot, which is the whole point of
     * modelling promotion as inventory.
     */
    public HomePage forCity(String city) {
        LocalDate today = LocalDate.now();
        String display = city == null ? "" : city.trim();
        boolean takenOver = placementService.isTakenOver(display, today);

        List<HomeSection> sections = new ArrayList<>();

        List<PropertySummary> hero = byIds(
                placementService.livePropertyIds(Surface.CITY_HOME_HERO, display, today));

        if (takenOver && !hero.isEmpty()) {
            sections.add(new HomeSection("takeover",
                    "Featured in " + display, "PROMOTED", hero));
            // Deliberately short. The advertiser bought the page; this exists so
            // the page is still navigable, not to compete with them.
            sections.add(new HomeSection("also-here", "Also available in " + display, "ORGANIC",
                    recentlyListed(display, 4)));
            return new HomePage(display, true, sections);
        }

        if (!hero.isEmpty()) {
            sections.add(new HomeSection("hero", "Featured in " + display, "PROMOTED", hero));
        }

        List<PropertySummary> featured = byIds(
                placementService.livePropertyIds(Surface.CITY_HOME_FEATURED, display, today));
        if (!featured.isEmpty()) {
            sections.add(new HomeSection("featured", "Promoted in " + display, "PROMOTED", featured));
        }

        // Popularity, narrowed to this city. Trending is computed nationally, so
        // it is filtered rather than recomputed — the Redis counter is global.
        List<PropertySummary> trending = discoveryService.trending(RAIL_SIZE * 3).stream()
                .filter(summary -> display.equalsIgnoreCase(summary.city()))
                .limit(RAIL_SIZE)
                .toList();
        if (!trending.isEmpty()) {
            sections.add(new HomeSection("trending", "Trending in " + display, "ORGANIC", trending));
        }

        sections.add(new HomeSection("recent", "Recently listed in " + display, "ORGANIC",
                recentlyListed(display, RAIL_SIZE)));

        return new HomePage(display, false, sections);
    }

    // -- helpers -------------------------------------------------------------

    /** Hydrates listing ids from the index, preserving the order given. */
    private List<PropertySummary> byIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> stringIds = ids.stream().map(UUID::toString).toList();

        Query query = Query.of(q -> q.bool(b -> b
                .filter(f -> f.terms(t -> t.field("id")
                        .terms(tv -> tv.value(stringIds.stream().map(FieldValue::of).toList()))))
                // A promoted listing that has since been taken down must not
                // appear just because the slot is still paid for.
                .filter(f -> f.term(t -> t.field("status").value(PropertyStatus.ACTIVE.name())))));

        SearchHits<PropertyDocument> hits = operations.search(
                new NativeQueryBuilder().withQuery(query).withMaxResults(stringIds.size()).build(),
                PropertyDocument.class);

        Map<String, PropertyDocument> byId = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toMap(PropertyDocument::getId, document -> document, (a, b) -> a));

        Set<String> seen = new LinkedHashSet<>();
        List<PropertySummary> ordered = new ArrayList<>();
        for (String id : stringIds) {
            PropertyDocument document = byId.get(id);
            if (document != null && seen.add(id)) {
                ordered.add(PropertySummaryMapper.toSummary(document));
            }
        }
        return ordered;
    }

    private List<PropertySummary> recentlyListed(String city, int limit) {
        Query query = Query.of(q -> q.bool(b -> {
            b.filter(f -> f.term(t -> t.field("status").value(PropertyStatus.ACTIVE.name())));
            if (city != null && !city.isBlank()) {
                b.filter(f -> f.term(t -> t.field("city").value(city.trim().toLowerCase())));
            }
            return b;
        }));

        SearchHits<PropertyDocument> hits = operations.search(
                new NativeQueryBuilder()
                        .withQuery(query)
                        .withSort(co.elastic.clients.elasticsearch._types.SortOptions.of(
                                s -> s.field(f -> f.field("publishedAt")
                                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))))
                        .withMaxResults(limit)
                        .build(),
                PropertyDocument.class);

        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(PropertySummaryMapper::toSummary)
                .toList();
    }
}
