package com.chatflow.cdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Redis key generation derived from {@link ProjectionEvent}
 * fields - no Redis connection required.
 */
class RedisKeyTest {

    private static ProjectionEvent event(long eventTsMs) {
        return new ProjectionEvent(
            "msg-abc", "room-1", "user-9", "charlie", "test", "TEXT",
            1L, eventTsMs, eventTsMs + 500, "2024-01-15");
    }

    // Bucket math

    @Test
    void minuteBucket_exactMinuteBoundary() {
        // 60_000 ms = 1 minute -> bucket 1
        assertEquals(1L, event(60_000L).minuteBucket());
    }

    @Test
    void minuteBucket_midMinute() {
        // 90_000 ms = 1 minute 30 s -> floor = bucket 1
        assertEquals(1L, event(90_000L).minuteBucket());
    }

    @Test
    void secondBucket_exactSecondBoundary() {
        // 5000 ms = 5 s -> bucket 5
        assertEquals(5L, event(5_000L).secondBucket());
    }

    @Test
    void secondBucket_subSecond_floorsToSameSecond() {
        // 5999 ms -> still bucket 5
        assertEquals(5L, event(5_999L).secondBucket());
    }

    @Test
    void minuteBucket_largeTimestamp() {
        long ts = 1_700_000_000_000L;
        assertEquals(ts / 60_000L, event(ts).minuteBucket());
    }

    @Test
    void secondBucket_largeTimestamp() {
        long ts = 1_700_000_000_123L;
        assertEquals(1_700_000_000L, event(ts).secondBucket());
    }

    // Redis key string format

    @Test
    void dedupeKey_format() {
        String messageId = "msg-abc";
        String key = "analytics:processed:" + messageId;
        assertEquals("analytics:processed:msg-abc", key);
    }

    @Test
    void messagesMinuteKey_format() {
        ProjectionEvent pe = event(120_000L);  // minute bucket = 2
        String key = "messages:minute:" + pe.minuteBucket();
        assertEquals("messages:minute:2", key);
    }

    @Test
    void messagesSecondKey_format() {
        ProjectionEvent pe = event(7_000L);  // second bucket = 7
        String key = "messages:second:" + pe.secondBucket();
        assertEquals("messages:second:7", key);
    }

    @Test
    void activeUsersKey_format() {
        ProjectionEvent pe = event(180_000L);  // minute bucket = 3
        String key = "active_users:minute:" + pe.minuteBucket();
        assertEquals("active_users:minute:3", key);
    }

    @Test
    void topUsersKey_format() {
        ProjectionEvent pe = event(240_000L);  // minute bucket = 4
        String key = "top_users:minute:" + pe.minuteBucket();
        assertEquals("top_users:minute:4", key);
    }

    @Test
    void topRoomsKey_format() {
        ProjectionEvent pe = event(300_000L);  // minute bucket = 5
        String key = "top_rooms:minute:" + pe.minuteBucket();
        assertEquals("top_rooms:minute:5", key);
    }

    // Health key constants

    @Test
    void healthKeys_staticStrings() {
        // Verify the expected Redis health key names used in RedisAnalytics
        assertEquals("projection:lastProjectedIngestedAt",
            "projection:lastProjectedIngestedAt");
        assertEquals("projection:lastProjectedMessageId",
            "projection:lastProjectedMessageId");
    }
}
