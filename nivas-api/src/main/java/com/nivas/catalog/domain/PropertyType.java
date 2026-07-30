package com.nivas.catalog.domain;

public enum PropertyType {
    APARTMENT,
    INDEPENDENT_HOUSE,
    VILLA,
    PLOT,
    STUDIO,
    PENTHOUSE,
    OFFICE,
    SHOP;

    /** A plot has no rooms, floors or furnishing; validation uses this to skip those checks. */
    public boolean isLand() {
        return this == PLOT;
    }

    public boolean isCommercial() {
        return this == OFFICE || this == SHOP;
    }
}
