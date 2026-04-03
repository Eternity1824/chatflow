package com.chatflow.consumerv3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    // ── disabled breaker ──────────────────────────────────────────────────────

    @Test
    void disabled_isOpenAlwaysFalse() {
        CircuitBreaker cb = new CircuitBreaker(false, 1, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen(), "disabled breaker must never open");
    }

    // ── normal CLOSED behaviour ───────────────────────────────────────────────

    @Test
    void belowThreshold_remainsClosed() {
        CircuitBreaker cb = new CircuitBreaker(true, 3, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen(), "should still be CLOSED below threshold");
    }

    @Test
    void atThreshold_opens() {
        CircuitBreaker cb = new CircuitBreaker(true, 3, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();  // hits threshold
        assertTrue(cb.isOpen(), "should open at threshold");
        assertEquals(1, cb.getOpenCount());
    }

    @Test
    void successResetsCounter() {
        CircuitBreaker cb = new CircuitBreaker(true, 3, 60_000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();  // reset
        cb.recordFailure();
        cb.recordFailure();
        assertFalse(cb.isOpen(), "counter reset by success — still 2 failures < threshold 3");
    }

    // ── auto-reset after openDurationMs ───────────────────────────────────────

    @Test
    void opensAndAutoResetsAfterDuration() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(true, 1, 30);  // 30ms open
        cb.recordFailure();
        assertTrue(cb.isOpen(), "should be OPEN immediately after threshold failure");

        Thread.sleep(50);  // wait longer than openDurationMs
        assertFalse(cb.isOpen(), "should auto-reset to CLOSED after open duration");
    }

    @Test
    void afterReset_canOpenAgain() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(true, 1, 20);
        cb.recordFailure();
        assertTrue(cb.isOpen());

        Thread.sleep(30);
        assertFalse(cb.isOpen(), "auto-reset");

        cb.recordFailure();  // trigger again
        assertTrue(cb.isOpen(), "should open again after reset");
        assertEquals(2, cb.getOpenCount());
    }

    // ── open count ────────────────────────────────────────────────────────────

    @Test
    void openCountIncrementsOnEachOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(true, 2, 10);
        cb.recordFailure(); cb.recordFailure();  // open #1
        assertTrue(cb.isOpen());

        Thread.sleep(20);
        // isOpen() call triggers the auto-reset from OPEN → CLOSED
        assertFalse(cb.isOpen(), "should auto-reset after duration");

        cb.recordFailure(); cb.recordFailure();  // open #2 (counter reset by isOpen auto-reset)
        assertEquals(2, cb.getOpenCount());
    }

    // ── stays OPEN within duration ────────────────────────────────────────────

    @Test
    void openDuringDuration_returnsTrue() {
        CircuitBreaker cb = new CircuitBreaker(true, 1, 60_000);  // 60s open
        cb.recordFailure();
        assertTrue(cb.isOpen());
        assertTrue(cb.isOpen(), "still open — duration not elapsed");
    }
}
