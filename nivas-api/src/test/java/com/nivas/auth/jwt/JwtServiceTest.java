package com.nivas.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nivas.auth.domain.Role;
import com.nivas.config.NivasProperties;
import com.nivas.security.AppUserPrincipal;

import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-definitely-long-enough-0123456789";

    private JwtService jwtService;
    private AppUserPrincipal principal;

    private static NivasProperties propertiesWith(Duration accessTtl, String issuer, String secret) {
        return new NivasProperties(
                new NivasProperties.Jwt(secret, issuer, accessTtl, Duration.ofDays(30)),
                new NivasProperties.Cache(Duration.ofMinutes(10), Duration.ofSeconds(60), Duration.ofHours(6)),
                new NivasProperties.RateLimit(
                        new NivasProperties.RateLimit.Rule(60, Duration.ofMinutes(1)),
                        new NivasProperties.RateLimit.Rule(5, Duration.ofHours(1)),
                        new NivasProperties.RateLimit.Rule(300, Duration.ofMinutes(1))),
                new NivasProperties.Search(100, 20));
    }

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(propertiesWith(Duration.ofMinutes(15), "nivas", SECRET));
        principal = new AppUserPrincipal(UUID.randomUUID(), "seeker@nivas.test", null, Role.SEEKER, true);
    }

    @Test
    @DisplayName("an access token round-trips its claims")
    void accessTokenRoundTrip() {
        String token = jwtService.issueAccessToken(principal);
        JwtService.ParsedToken parsed = jwtService.parseAccessToken(token);

        assertThat(parsed.userId()).isEqualTo(principal.getId());
        assertThat(parsed.email()).isEqualTo("seeker@nivas.test");
        assertThat(parsed.role()).isEqualTo(Role.SEEKER);
        assertThat(parsed.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("a refresh token round-trips its jti")
    void refreshTokenCarriesJti() {
        UUID tokenId = UUID.randomUUID();
        String token = jwtService.issueRefreshToken(principal.getId(), tokenId);

        assertThat(jwtService.parseRefreshToken(token).tokenId()).isEqualTo(tokenId);
    }

    @Test
    @DisplayName("a refresh token is rejected where an access token is required")
    void refreshTokenRejectedAsAccessToken() {
        String refresh = jwtService.issueRefreshToken(principal.getId(), UUID.randomUUID());

        // Without the type claim this would pass, silently granting a 30-day
        // token the authority of a 15-minute one.
        assertThatThrownBy(() -> jwtService.parseAccessToken(refresh))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("access");
    }

    @Test
    @DisplayName("an access token is rejected where a refresh token is required")
    void accessTokenRejectedAsRefreshToken() {
        String access = jwtService.issueAccessToken(principal);

        assertThatThrownBy(() -> jwtService.parseRefreshToken(access))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("refresh");
    }

    @Test
    @DisplayName("a token signed with another key is rejected")
    void foreignSignatureRejected() {
        JwtService attacker = new JwtService(
                propertiesWith(Duration.ofMinutes(15), "nivas", "a-completely-different-secret-key-0123456789"));
        String forged = attacker.issueAccessToken(principal);

        assertThatThrownBy(() -> jwtService.parseAccessToken(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void foreignIssuerRejected() {
        JwtService other = new JwtService(propertiesWith(Duration.ofMinutes(15), "someone-else", SECRET));
        String token = other.issueAccessToken(principal);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenRejected() {
        // Beyond the 30s clock skew allowance, so this is unambiguously expired.
        JwtService shortLived = new JwtService(propertiesWith(Duration.ofSeconds(-120), "nivas", SECRET));
        String token = shortLived.issueAccessToken(principal);

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("garbage input is rejected without leaking a stack trace shape")
    void garbageRejected() {
        assertThatThrownBy(() -> jwtService.parseAccessToken("not.a.jwt"))
                .isInstanceOfAny(JwtException.class, IllegalArgumentException.class);
    }
}
