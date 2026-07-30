package com.nivas.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.nivas.auth.domain.Role;
import com.nivas.config.NivasProperties;
import com.nivas.security.AppUserPrincipal;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Mints and verifies the two token types.
 *
 * <p>Access tokens are short-lived and self-contained — no store lookup, which is
 * what makes the API horizontally scalable. Refresh tokens are long-lived and
 * therefore <em>are</em> tracked server-side (see
 * {@link com.nivas.auth.RefreshTokenStore}); a stateless long-lived token cannot
 * be revoked, and "sign out everywhere" has to actually mean something.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "typ";

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final String issuer;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(NivasProperties properties) {
        NivasProperties.Jwt jwt = properties.jwt();
        this.key = Keys.hmacShaKeyFor(jwt.secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = jwt.issuer();
        this.accessTtl = jwt.accessTokenTtl();
        this.refreshTtl = jwt.refreshTokenTtl();
    }

    public String issueAccessToken(AppUserPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(principal.getId().toString())
                .claim(CLAIM_EMAIL, principal.getEmail())
                .claim(CLAIM_ROLE, principal.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * @param tokenId the jti this token is tracked under; the caller stores it so
     *                the token can later be revoked
     */
    public String issueRefreshToken(UUID userId, UUID tokenId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .id(tokenId.toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature, issuer and expiry, and checks the token is of the
     * expected type.
     *
     * <p>The type check is not ceremony: without it a refresh token — which lives
     * for a month — would be accepted as an access token, quietly turning the
     * short access-token lifetime into a fiction.
     */
    public ParsedToken parseAccessToken(String token) {
        return parse(token, TYPE_ACCESS);
    }

    public ParsedToken parseRefreshToken(String token) {
        return parse(token, TYPE_REFRESH);
    }

    private ParsedToken parse(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String type = claims.get(CLAIM_TYPE, String.class);
        if (!expectedType.equals(type)) {
            throw new JwtException("Expected a " + expectedType + " token but received " + type);
        }

        String roleName = claims.get(CLAIM_ROLE, String.class);
        return new ParsedToken(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                roleName == null ? null : Role.valueOf(roleName),
                claims.getId() == null ? null : UUID.fromString(claims.getId()),
                claims.getExpiration().toInstant());
    }

    public Duration accessTokenTtl() {
        return accessTtl;
    }

    public Duration refreshTokenTtl() {
        return refreshTtl;
    }

    public record ParsedToken(UUID userId, String email, Role role, UUID tokenId, Instant expiresAt) {
    }
}
