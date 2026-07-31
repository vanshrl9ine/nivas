package com.nivas.home.dto;

import java.util.List;

import com.nivas.search.dto.SearchDtos.PropertySummary;

public final class HomeDtos {

    private HomeDtos() {
    }

    /**
     * A home page, as a list of sections.
     *
     * <p>Sections rather than named fields so the layout is a server decision.
     * A takeover changes which sections exist, and a client rendering a fixed
     * set of slots could not express that without knowing about takeovers.
     */
    public record HomePage(
            /** Null for the all-India page. */
            String city,
            /**
             * True when one advertiser holds every hero slot for this city.
             * Clients use it to drop the usual chrome and give the page over.
             */
            boolean takeover,
            List<HomeSection> sections) {
    }

    public record HomeSection(
            String id,
            String title,
            /**
             * PROMOTED or ORGANIC.
             *
             * <p>Sent because paid placement has to be labelled as such. Burying
             * that distinction in the client is how a portal ends up presenting
             * advertising as an editorial recommendation.
             */
            String kind,
            List<PropertySummary> listings) {
    }
}
