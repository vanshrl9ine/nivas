package com.nivas.common.exception;

import java.time.Duration;

import org.springframework.http.HttpStatus;

/**
 * Carries the retry hint so the handler can emit a correct {@code Retry-After}
 * header rather than making the client guess and hammer us.
 */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;

    public RateLimitExceededException(String message, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
