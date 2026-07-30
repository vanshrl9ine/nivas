package com.nivas.analytics;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

/**
 * Counts listing views without touching Postgres on the read path.
 *
 * <p>A detail-page view is the single highest-frequency write in the product. An
 * {@code UPDATE … SET view_count = view_count + 1} per view would put a row lock
 * on the most popular listings and turn reads into writes on the primary. Instead
 * views accumulate in Redis and a scheduled job folds them back into Postgres in
 * batches — see {@link ViewCountFlusher}.
 *
 * <p>Two structures, for two questions:
 * <ul>
 * <li>a hash of pending deltas, which is what gets flushed and cleared;
 * <li>a per-day sorted set, which answers "what is trending" with a single
 * {@code ZREVRANGE} and expires on its own.
 * </ul>
 */
@Service
public class ViewCounterService {

    private static final Logger log = LoggerFactory.getLogger(ViewCounterService.class);

    static final String PENDING_VIEWS_KEY = "nivas:views:pending";
    private static final String TRENDING_KEY_PREFIX = "nivas:views:trending:";
    private static final String DEDUPE_KEY_PREFIX = "nivas:views:seen:";

    private static final Duration TRENDING_TTL = Duration.ofDays(8);
    private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public ViewCounterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records a view, ignoring repeats from the same viewer within
     * {@link #DEDUPE_WINDOW} so a refresh loop cannot inflate a listing's rank.
     *
     * @param viewerKey a stable per-viewer token (user id, or a hashed client
     *                  fingerprint for anonymous traffic)
     */
    public void recordView(UUID propertyId, String viewerKey) {
        try {
            String dedupeKey = DEDUPE_KEY_PREFIX + propertyId + ":" + viewerKey;
            Boolean firstTime = redis.opsForValue().setIfAbsent(dedupeKey, "1", DEDUPE_WINDOW);
            if (!Boolean.TRUE.equals(firstTime)) {
                return;
            }

            redis.opsForHash().increment(PENDING_VIEWS_KEY, propertyId.toString(), 1L);

            String trendingKey = trendingKey(LocalDate.now(ZoneOffset.UTC));
            redis.opsForZSet().incrementScore(trendingKey, propertyId.toString(), 1.0);
            redis.expire(trendingKey, TRENDING_TTL);

        } catch (RuntimeException ex) {
            // Analytics must never break a page load.
            log.debug("Could not record a view for {}", propertyId, ex);
        }
    }

    /** The most-viewed listings today, highest first. */
    public List<TrendingEntry> trendingToday(int limit) {
        return trendingFor(LocalDate.now(ZoneOffset.UTC), limit);
    }

    public List<TrendingEntry> trendingFor(LocalDate day, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(trendingKey(day), 0, Math.max(0, limit - 1));

        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .filter(tuple -> tuple.getValue() != null)
                .map(tuple -> new TrendingEntry(
                        UUID.fromString(tuple.getValue()),
                        tuple.getScore() == null ? 0L : tuple.getScore().longValue()))
                .toList();
    }

    /**
     * Atomically takes everything pending and resets the accumulator.
     *
     * <p>{@code RENAME} then read is the important part: reading and deleting
     * separately would drop every view recorded between the two calls.
     *
     * <p>The snapshot is <em>not</em> deleted here. The caller owns it until the
     * counts are durably in Postgres, and calls {@link #discardSnapshot} on
     * success or {@link #restoreSnapshot} on failure. Deleting eagerly would
     * throw the counts away precisely when the database write failed.
     */
    Snapshot drainPendingViews() {
        if (!Boolean.TRUE.equals(redis.hasKey(PENDING_VIEWS_KEY))) {
            return new Snapshot(null, Map.of());
        }

        String snapshotKey = PENDING_VIEWS_KEY + ":flushing:" + UUID.randomUUID();
        redis.rename(PENDING_VIEWS_KEY, snapshotKey);

        Map<Object, Object> raw = redis.opsForHash().entries(snapshotKey);
        Map<UUID, Long> deltas = raw.entrySet().stream().collect(Collectors.toMap(
                entry -> UUID.fromString(String.valueOf(entry.getKey())),
                entry -> Long.parseLong(String.valueOf(entry.getValue()))));

        return new Snapshot(snapshotKey, deltas);
    }

    void discardSnapshot(Snapshot snapshot) {
        if (snapshot.key() != null) {
            redis.delete(snapshot.key());
        }
    }

    /**
     * Folds a failed batch back into the live accumulator so it is retried on the
     * next flush. Views recorded in the meantime are added to, not overwritten.
     */
    void restoreSnapshot(Snapshot snapshot) {
        if (snapshot.key() == null) {
            return;
        }
        try {
            snapshot.deltas().forEach((propertyId, delta) ->
                    redis.opsForHash().increment(PENDING_VIEWS_KEY, propertyId.toString(), delta));
        } catch (RuntimeException ex) {
            log.error("Could not restore {} pending view counts; they are lost", snapshot.deltas().size(), ex);
        } finally {
            redis.delete(snapshot.key());
        }
    }

    record Snapshot(String key, Map<UUID, Long> deltas) {

        boolean isEmpty() {
            return deltas.isEmpty();
        }
    }

    private static String trendingKey(LocalDate day) {
        return TRENDING_KEY_PREFIX + day;
    }

    public record TrendingEntry(UUID propertyId, long views) {
    }
}
