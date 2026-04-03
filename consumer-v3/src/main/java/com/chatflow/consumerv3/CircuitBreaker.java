package com.chatflow.consumerv3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple count-based circuit breaker for DynamoDB write operations.
 *
 * <h3>States</h3>
 * <pre>
 *   CLOSED  ──(consecutiveFailures >= threshold)──▶  OPEN
 *   OPEN    ──(openDurationMs elapsed)────────────▶  CLOSED  (auto-reset)
 * </pre>
 *
 * <h3>Behavior when OPEN</h3>
 * The caller ({@link ConsumerV3App}) checks {@link #isOpen()} before draining
 * the accumulator.  If open, the drain is skipped — messages remain in the
 * {@link BatchAccumulator} (still unacked in RabbitMQ), applying natural
 * back-pressure via the prefetch limit.  No explicit nacks are issued.
 *
 * <h3>Half-open</h3>
 * Not implemented — the breaker resets directly from OPEN to CLOSED after
 * {@code openDurationMs}.  This is intentionally simple; half-open probing
 * can be added in a later phase if needed.
 *
 * <h3>Thread safety</h3>
 * All mutable state uses {@link AtomicInteger} / {@link AtomicLong} /
 * {@code volatile}.  The state-transition check-then-act in
 * {@link #recordFailure()} is intentionally non-atomic: the worst case is
 * opening the breaker one call late, which is acceptable.
 */
public class CircuitBreaker {

    private static final Logger log = LogManager.getLogger(CircuitBreaker.class);

    private enum State { CLOSED, OPEN }

    private volatile State        state              = State.CLOSED;
    private final AtomicInteger   consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong      openedAtMs          = new AtomicLong(0);
    private final AtomicLong      openCount           = new AtomicLong(0);

    private final int  failureThreshold;
    private final long openDurationMs;
    private final boolean enabled;

    public CircuitBreaker(boolean enabled, int failureThreshold, long openDurationMs) {
        this.enabled          = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs   = Math.max(1, openDurationMs);
    }

    /**
     * Returns {@code true} if the breaker is currently open (i.e., calls
     * should be rejected).  Automatically transitions OPEN → CLOSED once
     * {@code openDurationMs} has elapsed.
     */
    public boolean isOpen() {
        if (!enabled) return false;
        if (state == State.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAtMs.get();
            if (elapsed >= openDurationMs) {
                state = State.CLOSED;
                consecutiveFailures.set(0);
                log.info("CircuitBreaker: OPEN → CLOSED (reset after {}ms open)", elapsed);
            } else {
                log.debug("CircuitBreaker: OPEN, {}ms remaining",
                    openDurationMs - elapsed);
                return true;
            }
        }
        return false;
    }

    /**
     * Record a successful batch.  Resets the consecutive-failure counter.
     * Should be called whenever a batch completes with no terminal failures.
     */
    public void recordSuccess() {
        if (!enabled) return;
        consecutiveFailures.set(0);
    }

    /**
     * Record a batch that had at least one terminal failure.
     * Opens the breaker when consecutive failures reach the threshold.
     */
    public void recordFailure() {
        if (!enabled) return;
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state == State.CLOSED) {
            state = State.OPEN;
            openedAtMs.set(System.currentTimeMillis());
            openCount.incrementAndGet();
            log.warn("CircuitBreaker: CLOSED → OPEN (consecutiveFailures={}, threshold={})",
                failures, failureThreshold);
        }
    }

    /** Total number of times the breaker has opened since process start. */
    public long getOpenCount() { return openCount.get(); }

    public boolean isEnabled() { return enabled; }

    @Override
    public String toString() {
        return "CircuitBreaker{state=" + state
            + ", consecutiveFailures=" + consecutiveFailures.get()
            + ", threshold=" + failureThreshold
            + ", openDurationMs=" + openDurationMs
            + ", enabled=" + enabled + "}";
    }
}
