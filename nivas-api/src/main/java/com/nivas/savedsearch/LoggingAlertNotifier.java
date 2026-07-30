package com.nivas.savedsearch;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nivas.savedsearch.domain.SavedSearch;
import com.nivas.search.dto.SearchDtos.PropertySummary;

/**
 * Logs alerts instead of delivering them.
 *
 * <p>Deliberately not a real email integration. Sending mail properly means a
 * provider credential, a bounce policy, an unsubscribe link and a suppression
 * list — none of which is what this project sets out to demonstrate, and all of
 * which would be half-done. The seam is {@link AlertNotifier}, so adding real
 * delivery is a new bean and no change to {@link SavedSearchAlertJob}.
 */
@Component
public class LoggingAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertNotifier.class);

    @Override
    public void notifyNewMatches(SavedSearch savedSearch, List<PropertySummary> matches) {
        log.info("Alert for \"{}\" (user {}): {} new listing(s) — {}",
                savedSearch.getName(),
                savedSearch.getUser().getId(),
                matches.size(),
                matches.stream().map(PropertySummary::title).limit(5).toList());
    }
}
