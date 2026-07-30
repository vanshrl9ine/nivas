package com.nivas.savedsearch;

import java.util.List;

import com.nivas.savedsearch.domain.SavedSearch;
import com.nivas.search.dto.SearchDtos.PropertySummary;

/**
 * Where a saved-search alert goes.
 *
 * <p>A port rather than a direct email call, because delivery is the part most
 * likely to change (email now, push and in-app later) and the part that most
 * needs stubbing in tests. {@link SavedSearchAlertJob} depends only on this.
 *
 * <p>{@link LoggingAlertNotifier} is the only implementation shipped. To send
 * real notifications, add another bean and mark it {@code @Primary} — or delete
 * the logging one. Deliberately not wired with {@code @ConditionalOnMissingBean}:
 * outside auto-configuration its evaluation order is undefined, so it would work
 * by luck rather than by rule.
 */
public interface AlertNotifier {

    void notifyNewMatches(SavedSearch savedSearch, List<PropertySummary> matches);
}
