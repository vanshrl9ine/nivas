package com.nivas.outbox;

public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    /** Exhausted its retries. Left in place for inspection rather than dropped. */
    FAILED
}
