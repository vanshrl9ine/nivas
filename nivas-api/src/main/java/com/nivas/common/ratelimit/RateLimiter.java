package com.nivas.common.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.nivas.common.exception.RateLimitExceededException;

/**
 * Distributed sliding-window rate limiting.
 *
 * <p>The state lives in Redis rather than in a per-instance in-memory bucket, so
 * the limit is a property of the system and not of whichever node the load
 * balancer happened to pick.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String KEY_PREFIX = "nivas:rl:";

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    public RateLimiter(StringRedisTemplate redis, RedisScript<List> rateLimitScript) {
        this.redis = redis;
        this.script = rateLimitScript;
    }

    /**
     * Consumes one permit for {@code subject} on {@code bucket}.
     *
     * @throws RateLimitExceededException when the window is exhausted
     */
    public void consume(String bucket, String subject, int permits, Duration window) {
        Decision decision = tryConsume(bucket, subject, permits, window);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(
                    "Too many requests. Try again in %d seconds.".formatted(
                            Math.max(1, decision.retryAfter().toSeconds())),
                    decision.retryAfter());
        }
    }

    public Decision tryConsume(String bucket, String subject, int permits, Duration window) {
        String key = KEY_PREFIX + bucket + ":" + subject;
        long now = System.currentTimeMillis();

        try {
            List<?> result = redis.execute(script, List.of(key),
                    Long.toString(now),
                    Long.toString(window.toMillis()),
                    Integer.toString(permits),
                    // A unique member per request; two requests in the same
                    // millisecond must not collapse into one sorted-set entry.
                    now + "-" + UUID.randomUUID());

            if (result == null || result.size() < 3) {
                return Decision.allowedWith(permits);
            }

            boolean allowed = toLong(result.get(0)) == 1L;
            long remaining = toLong(result.get(1));
            long retryAfterMillis = toLong(result.get(2));
            return new Decision(allowed, remaining, Duration.ofMillis(retryAfterMillis));

        } catch (RuntimeException ex) {
            // Fail open. A rate limiter that takes the whole API down when Redis
            // hiccups is a worse outage than the abuse it was defending against.
            log.warn("Rate limit check failed for bucket {}; allowing the request", bucket, ex);
            return Decision.allowedWith(permits);
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    public record Decision(boolean allowed, long remaining, Duration retryAfter) {

        static Decision allowedWith(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }
    }
}
