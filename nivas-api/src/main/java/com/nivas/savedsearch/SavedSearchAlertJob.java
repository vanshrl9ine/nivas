package com.nivas.savedsearch;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.savedsearch.domain.SavedSearch;
import com.nivas.search.PropertySearchService;
import com.nivas.search.dto.SearchDtos.PropertySummary;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SearchResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Finds listings that have appeared since each saved search last notified its
 * owner, and hands them to {@link AlertNotifier}.
 *
 * <p>The watermark is the mechanism that makes this correct. Each search records
 * the instant it was last alerted up to, and the next run asks only for listings
 * published after it. That is what stops the job from re-sending the same
 * listings every tick — the failure mode that gets a product marked as spam.
 */
@Component
public class SavedSearchAlertJob {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchAlertJob.class);

    /** Per run, so one tick cannot monopolise Elasticsearch. */
    private static final int MAX_SEARCHES_PER_RUN = 200;

    /** How many listings to name in a single alert. */
    private static final int MAX_MATCHES_PER_ALERT = 10;

    private final SavedSearchRepository repository;
    private final SavedSearchService savedSearchService;
    private final PropertySearchService searchService;
    private final AlertNotifier notifier;
    private final Counter alertsSent;

    public SavedSearchAlertJob(SavedSearchRepository repository, SavedSearchService savedSearchService,
            PropertySearchService searchService, AlertNotifier notifier, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.savedSearchService = savedSearchService;
        this.searchService = searchService;
        this.notifier = notifier;
        this.alertsSent = Counter.builder("nivas.saved_search.alerts")
                .description("Saved-search alerts dispatched")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${nivas.saved-search.alert-interval:300000}")
    @Transactional
    public void dispatchDueAlerts() {
        Instant now = Instant.now();

        List<SavedSearch> candidates = repository.findAlertCandidates(
                PageRequest.of(0, MAX_SEARCHES_PER_RUN));

        int dispatched = 0;
        for (SavedSearch saved : candidates) {
            if (!saved.isDueAt(now)) {
                continue;
            }
            try {
                if (dispatch(saved)) {
                    dispatched++;
                }
            } catch (RuntimeException ex) {
                // One malformed or unlucky search must not stop the rest. The
                // watermark is left untouched so it is retried next tick.
                log.warn("Alert failed for saved search {}", saved.getId(), ex);
            }
        }

        if (dispatched > 0) {
            log.info("Dispatched {} saved-search alert(s)", dispatched);
        }
    }

    private boolean dispatch(SavedSearch saved) {
        SearchRequest criteria = savedSearchService.deserialise(saved.getCriteria());

        // Captured before the query runs. Advancing the watermark to a later
        // "now" would skip anything published while the search was executing.
        Instant ranAt = Instant.now();

        SearchRequest windowed = withWindow(criteria, saved.alertWatermark());
        SearchResponse response = searchService.search(windowed);
        List<PropertySummary> matches = response.results().content();

        if (matches.isEmpty()) {
            // Still advance: there was genuinely nothing, and not moving the
            // watermark would make the query scan an ever-widening window.
            saved.markAlerted(ranAt);
            repository.save(saved);
            return false;
        }

        notifier.notifyNewMatches(saved, matches);
        alertsSent.increment();

        saved.markAlerted(ranAt);
        repository.save(saved);
        return true;
    }

    private static SearchRequest withWindow(SearchRequest criteria, Instant since) {
        return new SearchRequest(
                criteria.q(), criteria.city(), criteria.localitySlugs(), criteria.listingType(),
                criteria.propertyTypes(), criteria.minPrice(), criteria.maxPrice(), criteria.bedrooms(),
                criteria.minAreaSqft(), criteria.maxAreaSqft(), criteria.furnishing(), criteria.amenities(),
                criteria.verifiedOnly(),
                since,
                criteria.lat(), criteria.lon(), criteria.radiusKm(),
                criteria.topLeftLat(), criteria.topLeftLon(), criteria.bottomRightLat(),
                criteria.bottomRightLon(),
                // Newest first regardless of the user's chosen sort: an alert is
                // about what is new, not about what is most relevant.
                com.nivas.search.dto.SearchDtos.SortOption.NEWEST,
                0, MAX_MATCHES_PER_ALERT);
    }
}
