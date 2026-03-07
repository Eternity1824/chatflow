package com.chatflow.serverv2;

public class PublishCircuitBreaker {
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openDurationMs;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openUntilMs = 0L;
    private boolean halfOpenProbeInFlight = false;

    public PublishCircuitBreaker(int failureThreshold, long openDurationMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs = Math.max(1_000L, openDurationMs);
    }

    public synchronized boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (now < openUntilMs) {
                return false;
            }
            state = State.HALF_OPEN;
            halfOpenProbeInFlight = false;
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenProbeInFlight) {
                return false;
            }
            halfOpenProbeInFlight = true;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            close();
            return;
        }
        if (state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    public synchronized void recordFailure() {
        long now = System.currentTimeMillis();
        if (state == State.HALF_OPEN) {
            open(now);
            return;
        }
        if (state == State.CLOSED) {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                open(now);
            }
        }
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized long getOpenUntilMs() {
        return openUntilMs;
    }

    private void open(long now) {
        state = State.OPEN;
        consecutiveFailures = 0;
        openUntilMs = now + openDurationMs;
        halfOpenProbeInFlight = false;
    }

    private void close() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openUntilMs = 0L;
        halfOpenProbeInFlight = false;
    }
}
