package com.nivas.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;
import com.nivas.common.exception.BadRequestException;

class PropertyTest {

    private static Property draft() {
        User owner = new User("owner@nivas.test", "hash", "Owner", null, Role.OWNER);
        return new Property(owner, "2 BHK in Koramangala", ListingType.RENT, PropertyType.APARTMENT,
                new BigDecimal("35000.00"), "Bengaluru", "Karnataka");
    }

    private static Property publishable() {
        Property property = draft();
        property.updateLocation(null, "80 Feet Road", "Bengaluru", "Karnataka", "560095", 12.9352, 77.6245);
        property.updateLayout((short) 2, (short) 2, (short) 1, Furnishing.SEMI_FURNISHED,
                1100, 1300, (short) 3, (short) 8, (short) 5);
        property.addImage(new PropertyImage("https://cdn.test/1.jpg", null, (short) 0, 1600, 900));
        property.transitionTo(PropertyStatus.PENDING_REVIEW);
        return property;
    }

    @Test
    @DisplayName("a new listing starts as a draft")
    void startsAsDraft() {
        assertThat(draft().getStatus()).isEqualTo(PropertyStatus.DRAFT);
    }

    @Test
    @DisplayName("publishing requires coordinates")
    void publishRequiresCoordinates() {
        Property property = draft();
        property.addImage(new PropertyImage("https://cdn.test/1.jpg", null, (short) 0, 100, 100));
        property.updateLayout((short) 2, (short) 2, null, null, 1100, null, null, null, null);
        property.transitionTo(PropertyStatus.PENDING_REVIEW);

        assertThatThrownBy(property::publish)
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("coordinates");
    }

    @Test
    @DisplayName("publishing requires at least one photo")
    void publishRequiresAPhoto() {
        Property property = draft();
        property.updateLocation(null, null, "Bengaluru", "Karnataka", null, 12.9, 77.6);
        property.updateLayout((short) 2, null, null, null, null, null, null, null, null);
        property.transitionTo(PropertyStatus.PENDING_REVIEW);

        assertThatThrownBy(property::publish)
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("photo");
    }

    @Test
    @DisplayName("publishing sets publishedAt and makes the listing searchable")
    void publishSucceeds() {
        Property property = publishable();
        property.publish();

        assertThat(property.getStatus()).isEqualTo(PropertyStatus.ACTIVE);
        assertThat(property.getPublishedAt()).isNotNull();
        assertThat(property.getStatus().isSearchable()).isTrue();
    }

    @Test
    @DisplayName("an illegal transition is rejected with a readable message")
    void illegalTransitionRejected() {
        Property property = draft();
        assertThatThrownBy(() -> property.transitionTo(PropertyStatus.SOLD))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("SOLD");
    }

    @Test
    @DisplayName("floor number above the building's total floors is rejected")
    void floorAboveTotalRejected() {
        Property property = draft();
        assertThatThrownBy(() -> property.updateLayout((short) 2, (short) 2, null, null,
                1000, null, (short) 12, (short) 8, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("total floors");
    }

    @Test
    @DisplayName("replacing images reassigns positions densely from zero")
    void imagePositionsAreDense() {
        Property property = draft();
        property.replaceImages(List.of(
                new PropertyImage("https://cdn.test/a.jpg", null, (short) 9, null, null),
                new PropertyImage("https://cdn.test/b.jpg", null, (short) 9, null, null),
                new PropertyImage("https://cdn.test/c.jpg", null, (short) 3, null, null)));

        assertThat(property.getImages()).extracting(PropertyImage::getPosition)
                .containsExactly((short) 0, (short) 1, (short) 2);
        assertThat(property.coverImage().getUrl()).isEqualTo("https://cdn.test/a.jpg");
    }

    @Test
    @DisplayName("price per sqft prefers carpet area over built-up")
    void pricePerSqftUsesCarpetArea() {
        Property property = draft();
        property.updatePricing(new BigDecimal("1000000"), null, null, false);
        property.updateLayout(null, null, null, null, 1000, 2000, null, null, null);

        assertThat(property.pricePerSqft()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("price per sqft is null when no area is recorded")
    void pricePerSqftNullWithoutArea() {
        Property property = draft();
        assertThat(property.pricePerSqft()).isNull();
    }

    @Test
    @DisplayName("a price change marks the listing for reindexing")
    void priceChangeTriggersReindex() {
        Property property = draft();
        assertThat(property.needsReindex()).isFalse();

        property.updatePricing(new BigDecimal("40000.00"), null, null, false);
        assertThat(property.needsReindex()).isTrue();
    }

    @Test
    @DisplayName("an unchanged price does not trigger a reindex")
    void unchangedPriceDoesNotTriggerReindex() {
        Property property = draft();
        property.updatePricing(new BigDecimal("35000.00"), null, null, false);

        assertThat(property.needsReindex())
                .as("re-saving the same price should not cost a reindex")
                .isFalse();
    }

    @Test
    @DisplayName("recording views never triggers a reindex")
    void viewsDoNotTriggerReindex() {
        Property property = draft();
        property.applyViewDelta(5_000);

        assertThat(property.getViewCount()).isEqualTo(5_000);
        assertThat(property.needsReindex()).isFalse();
    }

    @Test
    @DisplayName("replacing amenities with the same set does not trigger a reindex")
    void identicalAmenitiesDoNotTriggerReindex() {
        Property property = draft();
        property.replaceAmenities(EnumSet.of(Amenity.PARKING, Amenity.LIFT));
        assertThat(property.needsReindex()).isTrue();

        Property other = draft();
        other.replaceAmenities(EnumSet.noneOf(Amenity.class));
        assertThat(other.needsReindex()).isFalse();
    }
}
