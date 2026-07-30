package com.nivas.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.nivas.common.exception.RateLimitExceededException;
import com.nivas.common.ratelimit.RateLimiter;
import com.nivas.support.IntegrationTest;

/**
 * Exercises the Lua rate limiter against real Redis.
 *
 * <p>The concurrency test is the one that matters: a check-then-increment
 * implementation passes every sequential test here and still lets callers exceed
 * the limit under load. Only running requests in parallel demonstrates that the
 * script is genuinely atomic.
 */
@IntegrationTest
class RateLimiterIT {

    @Autowired
    private RateLimiter rateLimiter;

    private static String subject() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("permits are granted up to the limit and refused after it")
    void limitIsEnforced() {
        String subject = subject();
        int permits = 5;

        for (int i = 0; i < permits; i++) {
            int attempt = i + 1;
            assertThatNoException()
                    .as("request %d of %d should be allowed", attempt, permits)
                    .isThrownBy(() -> rateLimiter.consume("test", subject, permits, Duration.ofMinutes(1)));
        }

        assertThatThrownBy(() -> rateLimiter.consume("test", subject, permits, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("buckets are isolated per subject")
    void subjectsAreIndependent() {
        String exhausted = subject();
        rateLimiter.consume("test", exhausted, 1, Duration.ofMinutes(1));
        assertThatThrownBy(() -> rateLimiter.consume("test", exhausted, 1, Duration.ofMinutes(1)))
                .isInstanceOf(RateLimitExceededException.class);

        assertThatNoException()
                .as("one subject exhausting its bucket must not affect another")
                .isThrownBy(() -> rateLimiter.consume("test", subject(), 1, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("buckets are isolated per endpoint")
    void bucketsAreIndependent() {
        String subject = subject();
        rateLimiter.consume("search", subject, 1, Duration.ofMinutes(1));

        assertThatNoException()
                .as("exhausting the search bucket must not block lead submission")
                .isThrownBy(() -> rateLimiter.consume("lead", subject, 1, Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("the window slides, so permits return over time")
    void windowSlides() throws InterruptedException {
        String subject = subject();
        Duration window = Duration.ofMillis(700);

        rateLimiter.consume("test", subject, 1, window);
        assertThatThrownBy(() -> rateLimiter.consume("test", subject, 1, window))
                .isInstanceOf(RateLimitExceededException.class);

        Thread.sleep(window.toMillis() + 200);

        assertThatNoException()
                .as("the permit should be back once the window has passed")
                .isThrownBy(() -> rateLimiter.consume("test", subject, 1, window));
    }

    @Test
    @DisplayName("a rejected request reports a usable Retry-After")
    void retryAfterIsPopulated() {
        String subject = subject();
        rateLimiter.consume("test", subject, 1, Duration.ofMinutes(5));

        RateLimiter.Decision decision =
                rateLimiter.tryConsume("test", subject, 1, Duration.ofMinutes(5));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("concurrent callers cannot exceed the limit")
    void concurrentCallersCannotOvershoot() throws Exception {
        String subject = subject();
        int permits = 10;
        int attempts = 60;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> tasks = IntStream.range(0, attempts)
                    .<Callable<Boolean>>mapToObj(i -> () ->
                            rateLimiter.tryConsume("test", subject, permits, Duration.ofMinutes(1)).allowed())
                    .toList();

            long allowed = pool.invokeAll(tasks).stream()
                    .map(RateLimiterIT::get)
                    .filter(Boolean.TRUE::equals)
                    .count();

            assertThat(allowed)
                    .as("%d concurrent requests must not be granted more than %d permits", attempts, permits)
                    .isEqualTo(permits);
        }
    }

    private static Boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
