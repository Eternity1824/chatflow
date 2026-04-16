package com.chatflow.serverv2;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryService.expandDayBuckets(startMs, endMs).
 */
class DayBucketExpanderTest {

    /** A fixed instant at 2026-03-26 12:00:00 UTC */
    private static final long DAY_2026_03_26_NOON =
        ZonedDateTime.of(2026, 3, 26, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();

    @Test
    void sameDayReturnsSingleBucket() {
        long start = DAY_2026_03_26_NOON;
        long end   = start + 3_600_000L; // +1 hour, still same day
        List<String> buckets = QueryService.expandDayBuckets(start, end);
        assertEquals(1, buckets.size());
        assertEquals("20260326", buckets.get(0));
    }

    @Test
    void twoDaysReturnsTwoBuckets() {
        // start: 2026-03-25 23:30 UTC, end: 2026-03-26 00:30 UTC
        long start = ZonedDateTime.of(2026, 3, 25, 23, 30, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        long end   = ZonedDateTime.of(2026, 3, 26,  0, 30, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        List<String> buckets = QueryService.expandDayBuckets(start, end);
        assertEquals(2, buckets.size());
        assertEquals("20260325", buckets.get(0));
        assertEquals("20260326", buckets.get(1));
    }

    @Test
    void threeDaysReturnsThreeBuckets() {
        long start = ZonedDateTime.of(2026, 3, 24, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        long end   = ZonedDateTime.of(2026, 3, 26, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        List<String> buckets = QueryService.expandDayBuckets(start, end);
        assertEquals(3, buckets.size());
        assertEquals("20260324", buckets.get(0));
        assertEquals("20260325", buckets.get(1));
        assertEquals("20260326", buckets.get(2));
    }

    @Test
    void midnightBoundaryIncludesBothDays() {
        // Exactly at midnight 2026-03-26 00:00:00.000 UTC
        long midnight = ZonedDateTime.of(2026, 3, 26, 0, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        // start = 1ms before midnight (still 2026-03-25), end = midnight (2026-03-26)
        List<String> buckets = QueryService.expandDayBuckets(midnight - 1, midnight);
        assertEquals(2, buckets.size());
        assertEquals("20260325", buckets.get(0));
        assertEquals("20260326", buckets.get(1));
    }

    @Test
    void bucketFormatIsYYYYMMDD() {
        long start = DAY_2026_03_26_NOON;
        List<String> buckets = QueryService.expandDayBuckets(start, start);
        assertEquals(1, buckets.size());
        String bucket = buckets.get(0);
        // Must be exactly 8 digits
        assertTrue(bucket.matches("\\d{8}"), "Expected 8-digit YYYYMMDD but got: " + bucket);
        assertEquals("20260326", bucket);
    }

    @Test
    void sameStartAndEndReturnsSingleBucket() {
        long ts = DAY_2026_03_26_NOON;
        List<String> buckets = QueryService.expandDayBuckets(ts, ts);
        assertEquals(1, buckets.size());
    }

    // ── Sort-key boundary tests ─────────────────────────────────────────────

    @Test
    void startSortKeyFormat() {
        assertEquals("1700000000000#", QueryService.startSortKey(1700000000000L));
    }

    @Test
    void endSortKeyFormat() {
        String end = QueryService.endSortKey(1700000000000L);
        assertTrue(end.startsWith("1700000000000#"), "should start with tsMs#");
        assertTrue(end.compareTo("1700000000000#some-message-id") > 0,
            "endSortKey must be greater than any messageId suffix");
    }

    @Test
    void monthBoundaryIsHandledCorrectly() {
        // 2026-03-31 to 2026-04-01
        long start = ZonedDateTime.of(2026, 3, 31, 23, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        long end   = ZonedDateTime.of(2026, 4, 1, 1, 0, 0, 0, ZoneOffset.UTC)
            .toInstant().toEpochMilli();
        List<String> buckets = QueryService.expandDayBuckets(start, end);
        assertEquals(2, buckets.size());
        assertEquals("20260331", buckets.get(0));
        assertEquals("20260401", buckets.get(1));
    }
}
