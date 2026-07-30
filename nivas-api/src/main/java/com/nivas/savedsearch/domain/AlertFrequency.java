package com.nivas.savedsearch.domain;

import java.time.Duration;

/**
 * How often a saved search is allowed to notify its owner.
 *
 * <p>The interval is a <em>floor</em>, not a schedule: the job checks whether at
 * least this long has passed since the last alert. That keeps a slow or restarted
 * job from firing a burst of catch-up notifications.
 */
public enum AlertFrequency {

    /** Saved for convenience only; never alerts. */
    NONE(null),

    /**
     * As soon as the job notices a match. Still rate limited to once an hour —
     * "instant" in a property portal means "today", and a genuinely per-match
     * notification is how people unsubscribe.
     */
    INSTANT(Duration.ofHours(1)),

    DAILY(Duration.ofDays(1)),
    WEEKLY(Duration.ofDays(7));

    private final Duration minimumInterval;

    AlertFrequency(Duration minimumInterval) {
        this.minimumInterval = minimumInterval;
    }

    public boolean alerts() {
        return minimumInterval != null;
    }

    public Duration minimumInterval() {
        return minimumInterval;
    }
}
