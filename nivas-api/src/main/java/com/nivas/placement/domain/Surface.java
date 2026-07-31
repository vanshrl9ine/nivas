package com.nivas.placement.domain;

/**
 * The places a listing can be promoted into.
 *
 * <p>Mirrors the {@code placement_surfaces} reference table. Capacity is
 * deliberately *not* duplicated here: it is a commercial lever that should be
 * changeable without a deploy, so the database is the source of truth for it.
 */
public enum Surface {

    /** The single banner at the top of a city home page. */
    CITY_HOME_HERO(true),

    /** The featured rail on a city home page. */
    CITY_HOME_FEATURED(true),

    /** The rotating hero on the all-India home page. */
    NATIONAL_HOME_HERO(false),

    /** Promoted results above the organic hits. */
    SEARCH_TOP(true);

    private final boolean cityScoped;

    Surface(boolean cityScoped) {
        this.cityScoped = cityScoped;
    }

    /** National surfaces share one pool of slots; city surfaces have one pool each. */
    public boolean isCityScoped() {
        return cityScoped;
    }

    /**
     * The city key used for inventory.
     *
     * <p>Empty string, not null, for national surfaces — the exclusion
     * constraint compares cities with {@code =}, and NULL is never equal to
     * NULL, which would silently let every national booking overlap every other.
     */
    public String cityKey(String city) {
        if (!cityScoped) {
            return "";
        }
        return city == null ? "" : city.trim().toLowerCase();
    }
}
