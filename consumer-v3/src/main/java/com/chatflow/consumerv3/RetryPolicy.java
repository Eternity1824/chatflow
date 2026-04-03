package com.chatflow.consumerv3;

/**
 * Calculates retry delays and determines whether a message has exhausted
 * its retry budget.
 *
 * <p>Uses truncated exponential back-off with jitter:
 * <pre>
 *   delay = min(retryBaseMs * 2^(attempt-1), retryMaxMs) + jitter
 * </pre>
 *
 * TODO (Phase 2): wire into {@link PersistenceWriter} retry loop.
 */
public class RetryPolicy {

    private final long retryBaseMs;
    private final long retryMaxMs;
    private final int  maxRetries;

    public RetryPolicy(long retryBaseMs, long retryMaxMs, int maxRetries) {
        this.retryBaseMs = retryBaseMs;
        this.retryMaxMs  = retryMaxMs;
        this.maxRetries  = maxRetries;
    }

    /**
     * Returns {@code true} if another retry should be attempted for the given
     * attempt count (1-based: first attempt is 1).
     */
    public boolean shouldRetry(int attemptCount) {
        return attemptCount <= maxRetries;
    }

    /**
     * Calculates the delay in milliseconds before the next attempt.
     *
     * @param attemptCount the current attempt number (1-based)
     */
    public long delayMs(int attemptCount) {
        long exp   = retryBaseMs * (1L << Math.min(attemptCount - 1, 30));
        long capped = Math.min(exp, retryMaxMs);
        // add up to 20 % jitter
        long jitter = (long) (capped * 0.2 * Math.random());
        return capped + jitter;
    }

    /**
     * Block the calling thread for the computed delay.  Should be called from
     * a virtual thread so the platform thread is not blocked.
     *
     * TODO (Phase 2): replace with non-blocking scheduled executor if needed.
     */
    public void sleep(int attemptCount) throws InterruptedException {
        Thread.sleep(delayMs(attemptCount));
    }

    public int getMaxRetries() { return maxRetries; }
}
