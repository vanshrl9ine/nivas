package com.nivas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import com.nivas.catalog.domain.Amenity;
import com.nivas.catalog.domain.Furnishing;
import com.nivas.catalog.domain.ListingType;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.catalog.domain.PropertyType;
import com.nivas.catalog.dto.PropertyDtos.ImageResponse;
import com.nivas.catalog.dto.PropertyDtos.LocalityResponse;
import com.nivas.catalog.dto.PropertyDtos.OwnerResponse;
import com.nivas.catalog.dto.PropertyDtos.PropertyDetailResponse;

/**
 * Round-trips every shape the cache actually stores.
 *
 * <p>A cache serializer that can write but not read is invisible until something
 * gets a cache hit — the first request populates happily and the second one 500s.
 * That is exactly what happened: {@code DefaultTyping.NON_FINAL} writes no type
 * id for final types, every DTO here is a record, and records are final. So
 * nothing could be read back as {@code Object}.
 *
 * <p>Needs no Redis: the serializer is just bytes in, bytes out.
 */
class CacheSerializerRoundTripTest {

    private final GenericJacksonJsonRedisSerializer serializer = new RedisConfig().cacheValueSerializer();

    private <T> T roundTrip(T value) {
        byte[] written = serializer.serialize(value);
        assertThat(written).as("serialisation produced nothing").isNotEmpty();

        @SuppressWarnings("unchecked")
        T read = (T) serializer.deserialize(written);
        return read;
    }

    @Test
    @DisplayName("a list of records survives the round trip")
    void listOfRecords() {
        // The exact value cached by LocalityController.byCity, which was the
        // first thing to fail in production.
        List<LocalityResponse> localities = List.of(
                new LocalityResponse(UUID.randomUUID(), "bengaluru-koramangala", "Koramangala",
                        "Bengaluru", "Karnataka", 12.9352, 77.6245),
                new LocalityResponse(UUID.randomUUID(), "bengaluru-indiranagar", "Indiranagar",
                        "Bengaluru", "Karnataka", 12.9784, 77.6408));

        assertThat(roundTrip(localities)).isEqualTo(localities);
    }

    @Test
    @DisplayName("a list of strings survives the round trip")
    void listOfStrings() {
        // LocalityController.cities caches this shape.
        List<String> cities = List.of("Bengaluru", "Mumbai", "Pune");

        assertThat(roundTrip(cities)).isEqualTo(cities);
    }

    @Test
    @DisplayName("the full property detail payload survives the round trip")
    void propertyDetail() {
        PropertyDetailResponse detail = new PropertyDetailResponse(
                UUID.randomUUID(),
                "Bright 2 BHK in Koramangala",
                "A well-kept home.",
                ListingType.RENT,
                PropertyType.APARTMENT,
                PropertyStatus.ACTIVE,
                new BigDecimal("42000.00"),
                new BigDecimal("38.18"),
                new BigDecimal("2500.00"),
                new BigDecimal("84000.00"),
                true,
                java.time.LocalDate.of(2026, 9, 1),
                (short) 2, (short) 2, (short) 1,
                Furnishing.SEMI_FURNISHED,
                1100, 1350,
                (short) 4, (short) 10, (short) 6,
                new LocalityResponse(UUID.randomUUID(), "bengaluru-koramangala", "Koramangala",
                        "Bengaluru", "Karnataka", 12.9352, 77.6245),
                "80 Feet Road", "Bengaluru", "Karnataka", "560095",
                12.9352, 77.6245,
                null,
                true,
                1234L,
                Set.of(Amenity.PARKING, Amenity.LIFT),
                List.of(new ImageResponse(UUID.randomUUID(), "https://cdn.test/1.jpg", "Living room",
                        0, 1600, 900)),
                new OwnerResponse(UUID.randomUUID(), "Priya Sharma", "+919876543210"),
                Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-07-15T10:00:00Z"));

        PropertyDetailResponse read = roundTrip(detail);

        assertThat(read).isEqualTo(detail);
        // Spot-check the types most likely to be mangled by a JSON round trip.
        assertThat(read.price()).isEqualByComparingTo(detail.price());
        assertThat(read.publishedAt()).isEqualTo(detail.publishedAt());
        assertThat(read.amenities()).containsExactlyInAnyOrderElementsOf(detail.amenities());
        assertThat(read.availableFrom()).isEqualTo(detail.availableFrom());
    }

    @Test
    @DisplayName("an empty list survives the round trip")
    void emptyList() {
        assertThat(roundTrip(List.<String>of())).isEqualTo(List.of());
    }
}
