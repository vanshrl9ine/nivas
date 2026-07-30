package com.nivas.config;

/**
 * Cache names, centralised so a typo in an {@code @Cacheable} annotation cannot
 * silently create a fourth, unconfigured cache with the wrong TTL.
 */
public final class CacheNames {

    /** A single listing's public detail payload. Invalidated explicitly on write. */
    public static final String PROPERTY_DETAIL = "property-detail";

    /** Whole search responses, keyed by the normalised criteria. Very short TTL. */
    public static final String SEARCH_RESULTS = "search-results";

    /** Reference data. Changes rarely, read constantly. */
    public static final String LOCALITIES = "localities";

    private CacheNames() {
    }
}
