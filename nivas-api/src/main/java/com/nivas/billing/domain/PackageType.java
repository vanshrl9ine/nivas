package com.nivas.billing.domain;

/**
 * What a package actually sells.
 *
 * <p>The distinction matters because fulfilment differs entirely: a LISTING
 * package grants a quota and touches no inventory, while the other two book
 * finite slots that somebody else might be buying at the same moment.
 */
public enum PackageType {

    /** The right to have listings live at all. The free tier grants exactly one. */
    LISTING,

    /** Buys a slot or two on a surface. Competes with other boosts for position. */
    BOOST,

    /**
     * Takes over a city's home page — the hero and every featured slot.
     *
     * <p>Deliberately not a special case in code. It books all the inventory on
     * those surfaces, and the exclusion constraint on placements does the rest:
     * once the slots are gone there is nothing left for anyone else to buy.
     */
    AUDIENCE_MAXIMIZER;

    public boolean isPlacement() {
        return this != LISTING;
    }
}
