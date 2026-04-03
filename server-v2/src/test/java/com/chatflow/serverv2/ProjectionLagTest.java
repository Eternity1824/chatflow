package com.chatflow.serverv2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ProjectionHealthService static helpers computeLag() and computeConsistent().
 */
class ProjectionLagTest {

    // ── computeLag ────────────────────────────────────────────────────────────

    @Test
    void computeLag_zeroIngestedAt_returnsMinusOne() {
        long lag = ProjectionHealthService.computeLag(0L, System.currentTimeMillis());
        assertEquals(-1L, lag);
    }

    @Test
    void computeLag_negativeIngestedAt_returnsMinusOne() {
        long lag = ProjectionHealthService.computeLag(-1L, System.currentTimeMillis());
        assertEquals(-1L, lag);
    }

    @Test
    void computeLag_validIngestedAt_returnsNowMinusIngestedAt() {
        long nowMs       = 1_000_000L;
        long ingestedAt  = 999_000L;
        long lag = ProjectionHealthService.computeLag(ingestedAt, nowMs);
        assertEquals(1_000L, lag);
    }

    @Test
    void computeLag_ingestedAtEqualsNow_returnsZero() {
        long nowMs = 1_000_000L;
        long lag = ProjectionHealthService.computeLag(nowMs, nowMs);
        assertEquals(0L, lag);
    }

    @Test
    void computeLag_largeIngestedAt_returnsCorrectDifference() {
        long ingestedAt = 1_700_000_000_000L; // some epoch-ms
        long nowMs      = ingestedAt + 3_000L;
        long lag = ProjectionHealthService.computeLag(ingestedAt, nowMs);
        assertEquals(3_000L, lag);
    }

    // ── computeConsistent ─────────────────────────────────────────────────────

    @Test
    void computeConsistent_lagBelowThreshold_returnsTrue() {
        assertTrue(ProjectionHealthService.computeConsistent(1_000L, 5_000L));
    }

    @Test
    void computeConsistent_lagEqualsThreshold_returnsFalse() {
        // lag < threshold (strict less-than), so at boundary it is false
        assertFalse(ProjectionHealthService.computeConsistent(5_000L, 5_000L));
    }

    @Test
    void computeConsistent_lagAboveThreshold_returnsFalse() {
        assertFalse(ProjectionHealthService.computeConsistent(6_000L, 5_000L));
    }

    @Test
    void computeConsistent_lagIsMinusOne_returnsFalse() {
        // -1 indicates unknown; not consistent
        assertFalse(ProjectionHealthService.computeConsistent(-1L, 5_000L));
    }

    @Test
    void computeConsistent_lagIsZero_returnsTrue() {
        assertTrue(ProjectionHealthService.computeConsistent(0L, 5_000L));
    }

    @Test
    void computeConsistent_zeroThreshold_alwaysFalse() {
        // Nothing is < 0
        assertFalse(ProjectionHealthService.computeConsistent(0L, 0L));
        assertFalse(ProjectionHealthService.computeConsistent(1L, 0L));
    }
}
