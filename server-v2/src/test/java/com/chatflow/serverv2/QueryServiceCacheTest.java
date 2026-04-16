package com.chatflow.serverv2;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryService cache helpers: cache key construction,
 * cacheability predicate, and defensive copy behaviour.
 */
class QueryServiceCacheTest {

    // ── Cache key ────────────────────────────────────────────────────────────

    @Test
    void cacheKeyDistinguishesRoomAndUser() {
        String room = QueryService.cacheKey("room", "r1", 100, 200);
        String user = QueryService.cacheKey("user", "r1", 100, 200);
        assertNotEquals(room, user);
    }

    @Test
    void cacheKeyDistinguishesDifferentWindows() {
        String a = QueryService.cacheKey("room", "r1", 100, 200);
        String b = QueryService.cacheKey("room", "r1", 100, 300);
        assertNotEquals(a, b);
    }

    @Test
    void cacheKeyDistinguishesDifferentIds() {
        String a = QueryService.cacheKey("room", "r1", 100, 200);
        String b = QueryService.cacheKey("room", "r2", 100, 200);
        assertNotEquals(a, b);
    }

    @Test
    void cacheKeyFormat() {
        assertEquals("room:abc:100:200", QueryService.cacheKey("room", "abc", 100, 200));
    }

    // ── Cacheability predicate ───────────────────────────────────────────────

    @Test
    void historicalWindowIsCacheable() {
        long oldEnd = System.currentTimeMillis() - 60_000; // 60 s ago
        assertTrue(QueryService.isCacheable(oldEnd));
    }

    @Test
    void recentWindowIsNotCacheable() {
        long recentEnd = System.currentTimeMillis() - 5_000; // 5 s ago
        assertFalse(QueryService.isCacheable(recentEnd));
    }

    @Test
    void futureWindowIsNotCacheable() {
        long futureEnd = System.currentTimeMillis() + 60_000;
        assertFalse(QueryService.isCacheable(futureEnd));
    }

    // ── Defensive copy ──────────────────────────────────────────────────────

    @Test
    void immutableMessagesReturnsUnmodifiableList() {
        List<Map<String, Object>> src = new ArrayList<>();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messageId", "m1");
        msg.put("eventTs", 12345L);
        src.add(msg);

        List<Map<String, Object>> frozen = QueryService.immutableMessages(src);
        assertThrows(UnsupportedOperationException.class, () -> frozen.add(Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> frozen.get(0).put("x", "y"));
    }

    @Test
    void copyMessagesReturnsMutableIndependentCopy() {
        Map<String, Object> orig = new LinkedHashMap<>();
        orig.put("messageId", "m1");
        orig.put("eventTs", 100L);
        List<Map<String, Object>> src = List.of(Collections.unmodifiableMap(orig));

        List<Map<String, Object>> copy = QueryService.copyMessages(src);
        // Mutation of copy must not affect source
        copy.get(0).put("messageId", "MUTATED");
        assertEquals("m1", orig.get("messageId"));
    }

    @Test
    void copyMessagesPreservesKeyOrder() {
        Map<String, Object> orig = new LinkedHashMap<>();
        orig.put("messageId", "m1");
        orig.put("roomId", "r1");
        orig.put("eventTs", 100L);
        List<Map<String, Object>> copy = QueryService.copyMessages(List.of(orig));
        List<String> keys = new ArrayList<>(copy.get(0).keySet());
        assertEquals(List.of("messageId", "roomId", "eventTs"), keys);
    }

    // ── Cache integration (no DynamoDB) ──────────────────────────────────────

    @Test
    void cacheStatsAvailableOnDisabledService() {
        // null DynamoDbClient → enabled=false, but cache still initialised
        QueryService svc = new QueryService(null, null, null, null);
        assertNotNull(svc.messageQueryCacheStats());
        assertEquals(0, svc.messageQueryCacheStats().hitCount());
    }

    @Test
    void invalidateClearsCache() {
        QueryService svc = new QueryService(null, null, null, null);
        svc.invalidateMessageQueryCache();
        assertEquals(0, svc.messageQueryCacheStats().evictionCount());
    }
}
