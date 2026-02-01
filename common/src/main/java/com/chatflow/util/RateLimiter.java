package com.chatflow.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class RateLimiter {
    private final long intervalNs;
    private final AtomicLong nextAllowedNs = new AtomicLong(0L);

    private RateLimiter(long intervalNs) {
        this.intervalNs = intervalNs;
    }

    public static RateLimiter create(long permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be > 0");
        }
        long interval = 1_000_000_000L / permitsPerSecond;
        if (interval <= 0) {
            interval = 1;
        }
        return new RateLimiter(interval);
    }

    public void acquire() {
        long now;
        long next;
        long base;
        long newNext;
        do {
            now = System.nanoTime();
            next = nextAllowedNs.get();
            base = next > now ? next : now;
            newNext = base + intervalNs;
        } while (!nextAllowedNs.compareAndSet(next, newNext));

        long waitNs = base - now;
        if (waitNs > 0) {
            LockSupport.parkNanos(waitNs);
        }
    }
}
