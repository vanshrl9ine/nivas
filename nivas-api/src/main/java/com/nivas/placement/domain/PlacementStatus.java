package com.nivas.placement.domain;

public enum PlacementStatus {

    /**
     * Holding a slot. The exclusion constraint on placements only applies to
     * rows in this status, so cancelling one genuinely frees the inventory for
     * somebody else to buy.
     */
    ACTIVE,

    CANCELLED,
    EXPIRED
}
