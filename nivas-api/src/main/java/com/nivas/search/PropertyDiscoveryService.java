package com.nivas.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.springframework.data.elasticsearch.core.suggest.response.CompletionSuggestion;
import org.springframework.data.elasticsearch.core.suggest.response.Suggest;
import org.springframework.stereotype.Service;

import com.nivas.analytics.ViewCounterService;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.search.document.PropertyDocument;
import com.nivas.search.dto.SearchDtos.PropertySummary;
import com.nivas.search.dto.SearchDtos.SuggestionResponse;
import com.nivas.search.dto.SearchDtos.SuggestionResponse.Suggestion;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.CompletionContext;
import co.elastic.clients.elasticsearch.core.search.FieldSuggester;
import co.elastic.clients.elasticsearch.core.search.Suggester;

/**
 * The discovery surfaces around search: type-ahead, trending, and "more like
 * this one".
 *
 * <p>Separate from {@link PropertySearchService} because these answer a different
 * question. Search is "show me what matches these filters"; these are "help me
 * work out what to ask for". They also have quite different latency budgets — a
 * suggestion is on the critical path of a keystroke.
 */
@Service
public class PropertyDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(PropertyDiscoveryService.class);

    private static final String SUGGESTION_NAME = "listing-suggest";
    private static final int MAX_SUGGESTIONS = 8;
    private static final int MAX_SIMILAR = 6;

    private final ElasticsearchOperations operations;
    private final ViewCounterService viewCounter;
    private final SuggestCityScope cityScope;

    public PropertyDiscoveryService(ElasticsearchOperations operations, ViewCounterService viewCounter,
            SuggestCityScope cityScope) {
        this.operations = operations;
        this.viewCounter = viewCounter;
        this.cityScope = cityScope;
    }

    // -- type-ahead ----------------------------------------------------------

    /**
     * Completion suggestions for a partial query.
     *
     * <p>Uses the completion suggester rather than a prefix query on an
     * edge-ngram field. The suggester is backed by an in-memory FST built at
     * index time, so it answers in roughly constant time regardless of corpus
     * size — which is what makes it viable on every keystroke. A prefix query
     * would scan the inverted index instead.
     *
     * @param city optional; scopes suggestions using the category context in the
     *             mapping, so typing in the Pune UI does not surface Delhi
     */
    public SuggestionResponse suggest(String prefix, String city) {
        if (prefix == null || prefix.isBlank()) {
            return new SuggestionResponse(List.of());
        }

        Suggester suggester = Suggester.of(s -> s
                .suggesters(SUGGESTION_NAME, FieldSuggester.of(fs -> fs
                        .prefix(prefix.trim())
                        .completion(c -> {
                            c.field("suggest")
                                    .size(MAX_SUGGESTIONS)
                                    // The same phrasing is indexed for many listings
                                    // ("2 BHK in Koramangala"); without this the
                                    // dropdown is the same line eight times.
                                    .skipDuplicates(true)
                                    // One edit of tolerance. Enough for a slipped
                                    // key, tight enough that "villa" does not
                                    // suggest "ville".
                                    .fuzzy(f -> f.fuzziness("AUTO"));

                            /*
                             * Contexts are mandatory, not optional.
                             *
                             * The mapping declares a category context on this
                             * field, and Elasticsearch then *requires* one on
                             * every query — omitting it fails the shard outright
                             * rather than matching everything, which is the
                             * opposite of what "optional filter" would suggest.
                             *
                             * So an unscoped request supplies every known city
                             * instead of no context at all.
                             */
                            List<String> scope = (city == null || city.isBlank())
                                    ? knownCities()
                                    : List.of(city.trim().toLowerCase());

                            c.contexts("city", scope.stream()
                                    .map(name -> CompletionContext.of(
                                            ctx -> ctx.context(cv -> cv.category(name))))
                                    .toList());
                            return c;
                        }))));

        NativeQuery query = new NativeQueryBuilder()
                .withSuggester(suggester)
                // The suggester carries everything needed; asking for documents
                // as well would double the work for no benefit.
                .withMaxResults(0)
                .build();

        try {
            SearchHits<PropertyDocument> hits = operations.search(query, PropertyDocument.class);
            return new SuggestionResponse(readSuggestions(hits.getSuggest()));
        } catch (RuntimeException ex) {
            // A dropdown that fails should show nothing, not break the page the
            // user is typing into.
            log.warn("Suggestion lookup failed for prefix '{}'", prefix, ex);
            return new SuggestionResponse(List.of());
        }
    }

    /**
     * Every city with listings, lowercased to match the keyword normaliser.
     *
     * <p>Read from a terms aggregation rather than from Postgres so it reflects
     * what is actually in the index — a locality with no live listings would
     * contribute a context that matches nothing.
     *
     * <p>Cached on a separate bean, not with {@code @Cacheable} here.
     * {@code @Cacheable} is applied by a proxy, and this is called from
     * {@link #suggest} on {@code this}, so the annotation would be silently
     * ignored and every keystroke would run the aggregation.
     */
    List<String> knownCities() {
        return cityScope.lowercasedCityNames();
    }

    List<String> loadCityNames() {
        SearchHits<PropertyDocument> hits = operations.search(
                new NativeQueryBuilder()
                        .withQuery(Query.of(q -> q.term(t -> t.field("status")
                                .value(PropertyStatus.ACTIVE.name()))))
                        .withAggregation("cities", Aggregation.of(a -> a.terms(t -> t.field("city").size(100))))
                        .withMaxResults(0)
                        .build(),
                PropertyDocument.class);

        if (!hits.hasAggregations()
                || !(hits.getAggregations() instanceof ElasticsearchAggregations container)) {
            return List.of();
        }
        ElasticsearchAggregation cities = container.get("cities");
        if (cities == null || !cities.aggregation().getAggregate().isSterms()) {
            return List.of();
        }
        return cities.aggregation().getAggregate().sterms().buckets().array().stream()
                .map(bucket -> bucket.key().stringValue())
                .toList();
    }

    private List<Suggestion> readSuggestions(Suggest suggest) {
        if (suggest == null) {
            return List.of();
        }
        Suggest.Suggestion<? extends Suggest.Suggestion.Entry<? extends Suggest.Suggestion.Entry.Option>> suggestion =
                suggest.getSuggestion(SUGGESTION_NAME);
        if (suggestion == null) {
            return List.of();
        }

        // LinkedHashSet: skipDuplicates works per shard, so identical text can
        // still arrive twice from different shards. Insertion order preserves
        // Elasticsearch's ranking.
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> results = new ArrayList<>();

        suggestion.getEntries().forEach(entry -> entry.getOptions().forEach(option -> {
            if (!seen.add(option.getText())) {
                return;
            }
            String propertyId = null;
            String suggestionCity = null;

            if (option instanceof CompletionSuggestion.Entry.Option<?> completion) {
                SearchHit<?> hit = completion.getSearchHit();
                if (hit != null && hit.getContent() instanceof PropertyDocument document) {
                    propertyId = document.getId();
                    suggestionCity = document.getCity();
                }
            }
            results.add(new Suggestion(option.getText(), propertyId, suggestionCity));
        }));

        return results;
    }

    // -- trending ------------------------------------------------------------

    /**
     * The most-viewed listings today.
     *
     * <p>Ranking comes from the Redis sorted set rather than from the
     * {@code viewCount} field in the index, because that field is only refreshed
     * when a listing is reindexed. Redis has the live number; Elasticsearch has
     * the listing content. This reads the ranking from one and hydrates from the
     * other.
     */
    public List<PropertySummary> trending(int limit) {
        List<ViewCounterService.TrendingEntry> ranked = viewCounter.trendingToday(limit);
        if (ranked.isEmpty()) {
            return List.of();
        }

        List<String> ids = ranked.stream()
                .map(entry -> entry.propertyId().toString())
                .toList();

        Query byIds = Query.of(q -> q.bool(b -> b
                .filter(f -> f.terms(t -> t.field("id")
                        .terms(tv -> tv.value(ids.stream().map(FieldValue::of).toList()))))
                // A listing can be popular and then taken down; trending must not
                // resurrect it.
                .filter(f -> f.term(t -> t.field("status").value(PropertyStatus.ACTIVE.name())))));

        SearchHits<PropertyDocument> hits = operations.search(
                new NativeQueryBuilder().withQuery(byIds).withMaxResults(ids.size()).build(),
                PropertyDocument.class);

        Map<String, PropertyDocument> byId = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toMap(PropertyDocument::getId, document -> document, (a, b) -> a));

        // Re-ordered to match Redis, since a terms query returns in index order.
        return ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(PropertySummaryMapper::toSummary)
                .toList();
    }

    // -- similar listings ----------------------------------------------------

    /**
     * Listings a viewer of this one would plausibly also consider.
     *
     * <p>Similarity here is not only textual. {@code more_like_this} finds
     * listings described in comparable terms, and that is then reweighted by two
     * things a mover actually cares about: how close it is, and whether it is in
     * the same budget. A beautifully-worded match forty kilometres away is not a
     * useful suggestion.
     *
     * <p>Hard filters keep it honest — same listing type, and never the listing
     * being viewed.
     */
    public List<PropertySummary> similarTo(UUID propertyId) {
        PropertyDocument source = operations.get(propertyId.toString(), PropertyDocument.class);
        if (source == null) {
            return List.of();
        }

        Query moreLikeThis = Query.of(q -> q.moreLikeThis(m -> m
                .fields("title", "description", "localityName.text")
                .like(l -> l.document(d -> d.index(PropertyDocument.INDEX_NAME).id(source.getId())))
                // Defaults of 2/5 are tuned for large corpora of long documents.
                // Listing descriptions are short, so a term appearing once in a
                // handful of listings is still a signal.
                .minTermFreq(1)
                .minDocFreq(1)
                .maxQueryTerms(25)
                .minimumShouldMatch("20%")));

        List<FunctionScore> proximity = new ArrayList<>();

        if (source.getLocation() != null) {
            // Halves the score by ~5km out. Decays rather than filters, so a
            // strong match slightly further away can still surface.
            proximity.add(FunctionScore.of(fn -> fn.gauss(g -> g.geo(geo -> geo
                    .field("location")
                    .placement(p -> p
                            .origin(co.elastic.clients.elasticsearch._types.GeoLocation.of(
                                    loc -> loc.latlon(ll -> ll
                                            .lat(source.getLocation().getLat())
                                            .lon(source.getLocation().getLon()))))
                            .scale("5km")
                            .offset("1km")
                            .decay(0.5))))));
        }

        if (source.getPrice() != null) {
            double price = source.getPrice().doubleValue();
            // Scaled to the source price rather than a fixed rupee amount: 20%
            // means the same thing to someone looking at 30k rent and 3 crore.
            proximity.add(FunctionScore.of(fn -> fn.gauss(g -> g.numeric(n -> n
                    .field("price")
                    .placement(p -> p
                            .origin(price)
                            .scale(Math.max(price * 0.25, 1))
                            .offset(price * 0.10)
                            .decay(0.5))))));
        }

        Query scored = proximity.isEmpty()
                ? moreLikeThis
                : Query.of(q -> q.functionScore(fs -> fs
                        .query(moreLikeThis)
                        .functions(proximity)
                        .scoreMode(FunctionScoreMode.Multiply)
                        .boostMode(FunctionBoostMode.Multiply)));

        Query withGuards = Query.of(q -> q.bool(b -> b
                .must(scored)
                .filter(f -> f.term(t -> t.field("status").value(PropertyStatus.ACTIVE.name())))
                .filter(f -> f.term(t -> t.field("listingType").value(source.getListingType())))
                // Excluding the source is not optional: it is by definition its
                // own best match and would always rank first.
                .mustNot(f -> f.term(t -> t.field("id").value(source.getId())))));

        SearchHits<PropertyDocument> hits = operations.search(
                new NativeQueryBuilder()
                        .withQuery(withGuards)
                        .withPageable(PageRequest.of(0, MAX_SIMILAR))
                        .build(),
                PropertyDocument.class);

        return hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(PropertySummaryMapper::toSummary)
                .toList();
    }
}
