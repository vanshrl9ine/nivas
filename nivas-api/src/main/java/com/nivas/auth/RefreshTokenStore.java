package com.nivas.auth;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Server-side record of which refresh tokens are still live.
 *
 * <p>Redis rather than Postgres because the access pattern is a keyed lookup on
 * every refresh with a natural expiry, which is exactly what {@code SETEX} does
 * for free — no cleanup job, no growing table.
 *
 * <p>Two keys per session:
 * <ul>
 * <li>{@code refresh:<jti>} → the owning user id. This is the one consulted on
 * every refresh.
 * <li>{@code refresh:user:<userId>} → the set of that user's live jtis, which is
 * what makes "sign out of all devices" possible at all.
 * </ul>
 */
@Component
public class RefreshTokenStore {

    private static final String TOKEN_KEY_PREFIX = "nivas:refresh:";
    private static final String USER_KEY_PREFIX = "nivas:refresh:user:";

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void store(UUID userId, UUID tokenId, Duration ttl) {
        redis.opsForValue().set(tokenKey(tokenId), userId.toString(), ttl);
        redis.opsForSet().add(userKey(userId), tokenId.toString());
        // The index must outlive the longest-lived token it points at, otherwise
        // a still-valid session would silently drop out of "sign out everywhere".
        redis.expire(userKey(userId), ttl);
    }

    /** @return true when this jti is still live and belongs to the claimed user */
    public boolean isActive(UUID userId, UUID tokenId) {
        String stored = redis.opsForValue().get(tokenKey(tokenId));
        return userId.toString().equals(stored);
    }

    public void revoke(UUID userId, UUID tokenId) {
        redis.delete(tokenKey(tokenId));
        redis.opsForSet().remove(userKey(userId), tokenId.toString());
    }

    /** Signs the user out everywhere. Used on logout-all and password change. */
    public long revokeAll(UUID userId) {
        Set<String> tokenIds = redis.opsForSet().members(userKey(userId));
        if (tokenIds == null || tokenIds.isEmpty()) {
            redis.delete(userKey(userId));
            return 0;
        }
        long removed = tokenIds.stream()
                .map(id -> TOKEN_KEY_PREFIX + id)
                .map(redis::delete)
                .filter(Boolean.TRUE::equals)
                .count();
        redis.delete(userKey(userId));
        return removed;
    }

    private static String tokenKey(UUID tokenId) {
        return TOKEN_KEY_PREFIX + tokenId;
    }

    private static String userKey(UUID userId) {
        return USER_KEY_PREFIX + userId;
    }
}
