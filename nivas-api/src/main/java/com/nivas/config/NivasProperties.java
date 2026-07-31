package com.nivas.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Every knob the application exposes, bound once and validated at startup.
 *
 * <p>Binding these as records means a typo in {@code application.yml} fails the
 * context refresh with a precise message instead of silently leaving a field null
 * and blowing up on the first request that touches it.
 */
@ConfigurationProperties(prefix = "nivas")
@Validated
public record NivasProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cache cache,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Search search) {

    public record Jwt(
            /* HS256 needs >= 256 bits of key material; anything shorter is rejected by JJWT anyway. */
            @NotBlank @Size(min = 32, message = "JWT secret must be at least 32 characters") String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl) {
    }

    public record Cache(
            @NotNull Duration propertyDetailTtl,
            @NotNull Duration searchResultsTtl,
            @NotNull Duration localityTtl) {
    }

    public record RateLimit(
            @Valid @NotNull Rule search,
            @Valid @NotNull Rule lead,
            /*
             * Type-ahead gets its own bucket. Sharing the search one would mean
             * typing "koramangala" burns eleven search permits before the user
             * has even pressed enter.
             */
            @Valid @NotNull Rule suggest) {

        public record Rule(@Min(1) int permits, @NotNull Duration window) {
        }
    }

    public record Search(
            @Min(1) @DefaultValue("100") int maxPageSize,
            @Min(1) @DefaultValue("20") int defaultPageSize) {
    }
}
