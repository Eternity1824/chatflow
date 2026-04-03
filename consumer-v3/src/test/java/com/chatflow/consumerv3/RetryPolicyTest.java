package com.chatflow.consumerv3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    // ── shouldRetry ───────────────────────────────────────────────────────────

    @Test
    void shouldRetry_withinBudget_returnsTrue() {
        RetryPolicy rp = new RetryPolicy(50, 5_000, 3);
        assertTrue(rp.shouldRetry(1));
        assertTrue(rp.shouldRetry(2));
        assertTrue(rp.shouldRetry(3));
    }

    @Test
    void shouldRetry_beyondBudget_returnsFalse() {
        RetryPolicy rp = new RetryPolicy(50, 5_000, 3);
        assertFalse(rp.shouldRetry(4));
        assertFalse(rp.shouldRetry(100));
    }

    @Test
    void shouldRetry_zeroMaxRetries_alwaysFalse() {
        RetryPolicy rp = new RetryPolicy(50, 5_000, 0);
        assertFalse(rp.shouldRetry(1));
    }

    // ── delayMs ───────────────────────────────────────────────────────────────

    @Test
    void delayMs_attempt1_aroundBase() {
        RetryPolicy rp = new RetryPolicy(100, 10_000, 5);
        long delay = rp.delayMs(1);
        // base * 2^0 = 100, plus up to 20% jitter = [100, 120]
        assertTrue(delay >= 100 && delay <= 120,
            "attempt=1 delay should be in [100,120], got " + delay);
    }

    @Test
    void delayMs_attempt2_doubled() {
        RetryPolicy rp = new RetryPolicy(100, 10_000, 5);
        long delay = rp.delayMs(2);
        // base * 2^1 = 200, plus up to 20% jitter = [200, 240]
        assertTrue(delay >= 200 && delay <= 240,
            "attempt=2 delay should be in [200,240], got " + delay);
    }

    @Test
    void delayMs_highAttempt_cappedAtMax() {
        RetryPolicy rp = new RetryPolicy(100, 1_000, 20);
        long delay = rp.delayMs(15);
        // exp = 100 * 2^14 → huge, capped at 1000, jitter <= 200
        assertTrue(delay >= 1_000 && delay <= 1_200,
            "delay should be capped at max + jitter, got " + delay);
    }

    @Test
    void delayMs_alwaysPositive() {
        RetryPolicy rp = new RetryPolicy(1, 100, 5);
        for (int i = 1; i <= 10; i++) {
            assertTrue(rp.delayMs(i) >= 1, "delay must be >= 1 at attempt=" + i);
        }
    }

    // ── sleep (fast path — just verify it doesn't throw for very short delay) ──

    @Test
    void sleep_shortDelay_completesWithoutException() throws InterruptedException {
        RetryPolicy rp = new RetryPolicy(1, 5, 3);
        // delay(1) ≈ 1ms — should complete quickly
        rp.sleep(1);
    }
}
