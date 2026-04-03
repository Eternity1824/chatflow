package com.chatflow.cdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link RedisAnalytics#buildBatchAnalyticsArgs} produces the
 * correct Lua {@code EVAL} payload for batch analytics writes.
 *
 * <p>Argument ordering matters: the Lua script reads header fields first, then
 * consumes fixed-width record entries.
 */
class LuaScriptKeyTest {

    // eventTsMs = 120_500 ms -> minuteBucket = 2, secondBucket = 120
    private static final ProjectionEvent PE = new ProjectionEvent(
        "msg-abc", "room-9", "user-7", "alice", "hello", "TEXT",
        3L, 120_500L, 121_000L, "1970-01-01");

    private static final ProjectionEvent PE2 = new ProjectionEvent(
        "msg-def", "room-2", "user-8", "bob", "hi", "TEXT",
        4L, 180_999L, 190_000L, "1970-01-01");

    @Test
    void args_header_containsTtlHealthAndCount() {
        String[] args = RedisAnalytics.buildBatchAnalyticsArgs(java.util.List.of(PE, PE2), 3600);
        assertEquals("3600", args[0]);
        assertEquals("190000", args[1]);
        assertEquals("msg-def", args[2]);
        assertEquals("2", args[3]);
    }

    @Test
    void args_firstRecord_containsMessageAndBuckets() {
        String[] args = RedisAnalytics.buildBatchAnalyticsArgs(java.util.List.of(PE), 3600);
        assertEquals("msg-abc", args[4]);
        assertEquals("user-7", args[5]);
        assertEquals("room-9", args[6]);
        assertEquals("2", args[7]);
        assertEquals("120", args[8]);
    }

    @Test
    void args_multipleRecords_areAppendedInOrder() {
        String[] args = RedisAnalytics.buildBatchAnalyticsArgs(java.util.List.of(PE, PE2), 3600);
        assertEquals("msg-def", args[9]);
        assertEquals("user-8", args[10]);
        assertEquals("room-2", args[11]);
        assertEquals("3", args[12]);
        assertEquals("180", args[13]);
    }

    @Test
    void latestByIngestedAt_picksNewestEvent() {
        ProjectionEvent latest = RedisAnalytics.latestByIngestedAt(java.util.List.of(PE, PE2));
        assertSame(PE2, latest);
    }

    @Test
    void latestByIngestedAt_emptyList_returnsNull() {
        assertNull(RedisAnalytics.latestByIngestedAt(java.util.List.of()));
    }

    // Script constant sanity

    @Test
    void analyticsScript_notBlank() {
        assertFalse(RedisAnalytics.ANALYTICS_SCRIPT.isBlank());
    }

    @Test
    void analyticsScript_referencesBatchHeaderArgs() {
        String script = RedisAnalytics.ANALYTICS_SCRIPT;
        assertTrue(script.contains("ARGV[1]"), "ARGV[1] missing");
        assertTrue(script.contains("ARGV[2]"), "ARGV[2] missing");
        assertTrue(script.contains("ARGV[3]"), "ARGV[3] missing");
        assertTrue(script.contains("ARGV[4]"), "ARGV[4] missing");
    }

    @Test
    void analyticsScript_referencesAllRedisStructures() {
        String script = RedisAnalytics.ANALYTICS_SCRIPT;
        assertTrue(script.contains("analytics:processed:"), "dedupe key missing");
        assertTrue(script.contains("active_users:minute:"), "active user key missing");
        assertTrue(script.contains("top_users:minute:"), "top user key missing");
        assertTrue(script.contains("top_rooms:minute:"), "top room key missing");
        assertTrue(script.contains("messages:second:"), "per-second key missing");
        assertTrue(script.contains("messages:minute:"), "per-minute key missing");
        assertTrue(script.contains("projection:lastProjectedIngestedAt"), "health ts key missing");
        assertTrue(script.contains("projection:lastProjectedMessageId"), "health id key missing");
    }

    @Test
    void analyticsScript_countsFirstSeenRecords() {
        String script = RedisAnalytics.ANALYTICS_SCRIPT;
        assertTrue(script.contains("firstSeen = firstSeen + 1"), "should count first-seen records");
        assertTrue(script.contains("return firstSeen"), "should return aggregate first-seen count");
    }
}
