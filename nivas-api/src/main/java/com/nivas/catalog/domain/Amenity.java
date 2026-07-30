package com.nivas.catalog.domain;

/**
 * Amenities are a closed vocabulary, not free text.
 *
 * <p>That is a product decision as much as a technical one: a fixed set is what
 * makes amenity filters a cheap terms aggregation in Elasticsearch instead of an
 * unbounded, misspelling-riddled facet.
 */
public enum Amenity {
    PARKING,
    LIFT,
    POWER_BACKUP,
    SECURITY,
    GYM,
    SWIMMING_POOL,
    CLUBHOUSE,
    PARK,
    GAS_PIPELINE,
    WATER_SUPPLY_24X7,
    RAIN_WATER_HARVESTING,
    VASTU_COMPLIANT,
    PET_FRIENDLY,
    WHEELCHAIR_ACCESSIBLE,
    GATED_COMMUNITY
}
