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
        @Valid @NotNull Search search,
        @Valid @NotNull Payments payments) {

    public record Payments(
            /** Which gateway bean is active: {@code mock} or {@code razorpay}. */
            @NotBlank String gateway,

            /**
             * HMAC secret the provider signs webhook bodies with. Without a
             * correct one the webhook endpoint would be an unauthenticated
             * "grant me a package" API, so it is validated for length rather
             * than merely being present.
             */
            @NotBlank @Size(min = 16, message = "Webhook secret must be at least 16 characters")
            String webhookSecret,

            /** Provider API key id. Unused by the mock gateway. */
            String keyId,

            /** Provider API secret. Never logged, never returned by any endpoint. */
            String keySecret,

            /** How long an unpaid order holds its slots before being swept. */
            @NotNull Duration orderTtl) {
    }

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
