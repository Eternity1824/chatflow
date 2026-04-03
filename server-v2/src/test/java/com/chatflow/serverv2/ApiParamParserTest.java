package com.chatflow.serverv2;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ApiHandler parameter parsing utilities.
 */
class ApiParamParserTest {

    // ── parseQueryString ──────────────────────────────────────────────────────

    @Test
    void parseQueryString_multipleParams() {
        Map<String, String> params = ApiHandler.parseQueryString("start=1000&end=2000&topN=5");
        assertEquals("1000", params.get("start"));
        assertEquals("2000", params.get("end"));
        assertEquals("5", params.get("topN"));
        assertEquals(3, params.size());
    }

    @Test
    void parseQueryString_emptyString() {
        Map<String, String> params = ApiHandler.parseQueryString("");
        assertTrue(params.isEmpty());
    }

    @Test
    void parseQueryString_nullString() {
        Map<String, String> params = ApiHandler.parseQueryString(null);
        assertTrue(params.isEmpty());
    }

    @Test
    void parseQueryString_singleParam() {
        Map<String, String> params = ApiHandler.parseQueryString("roomId=room123");
        assertEquals("room123", params.get("roomId"));
    }

    @Test
    void parseQueryString_noEqualsSign_ignored() {
        // Entry without '=' should be ignored
        Map<String, String> params = ApiHandler.parseQueryString("novalue&key=val");
        assertNull(params.get("novalue"));
        assertEquals("val", params.get("key"));
    }

    // ── parseLong ─────────────────────────────────────────────────────────────

    @Test
    void parseLong_validNumber() {
        assertEquals(12345L, ApiHandler.parseLong("12345", 0L));
    }

    @Test
    void parseLong_nullUsesDefault() {
        assertEquals(999L, ApiHandler.parseLong(null, 999L));
    }

    @Test
    void parseLong_blankUsesDefault() {
        assertEquals(999L, ApiHandler.parseLong("  ", 999L));
    }

    @Test
    void parseLong_invalidUsesDefault() {
        assertEquals(42L, ApiHandler.parseLong("notanumber", 42L));
    }

    @Test
    void parseLong_withWhitespace() {
        assertEquals(100L, ApiHandler.parseLong(" 100 ", 0L));
    }

    // ── parseInt ──────────────────────────────────────────────────────────────

    @Test
    void parseInt_validNumber() {
        assertEquals(10, ApiHandler.parseInt("10", 0));
    }

    @Test
    void parseInt_nullUsesDefault() {
        assertEquals(5, ApiHandler.parseInt(null, 5));
    }

    @Test
    void parseInt_invalidUsesDefault() {
        assertEquals(7, ApiHandler.parseInt("bad", 7));
    }

    // ── parseTimeRange ────────────────────────────────────────────────────────

    @Test
    void parseTimeRange_endLessThanStart_returnsNull() {
        Map<String, String> params = Map.of("start", "2000", "end", "1000");
        long[] result = ApiHandler.parseTimeRange(params);
        assertNull(result);
    }

    @Test
    void parseTimeRange_validRange() {
        Map<String, String> params = Map.of("start", "1000", "end", "2000");
        long[] result = ApiHandler.parseTimeRange(params);
        assertNotNull(result);
        assertEquals(1000L, result[0]);
        assertEquals(2000L, result[1]);
    }

    @Test
    void parseTimeRange_equalStartAndEnd_returnsRange() {
        Map<String, String> params = Map.of("start", "5000", "end", "5000");
        long[] result = ApiHandler.parseTimeRange(params);
        assertNotNull(result);
        assertEquals(5000L, result[0]);
        assertEquals(5000L, result[1]);
    }

    @Test
    void parseTimeRange_missingStartDefaultsToFifteenMinutesAgo() {
        long before = System.currentTimeMillis();
        Map<String, String> params = Map.of("end", String.valueOf(before + 10_000));
        long[] result = ApiHandler.parseTimeRange(params);
        assertNotNull(result);
        // start should be approximately now - 15min
        long expectedStart = before - ApiHandler.DEFAULT_WINDOW_MS;
        // Allow 1-second tolerance for test timing
        assertTrue(Math.abs(result[0] - expectedStart) < 1_000,
            "Expected start ~" + expectedStart + " but got " + result[0]);
    }

    @Test
    void parseTimeRange_missingEndDefaultsToNow() {
        long before = System.currentTimeMillis();
        Map<String, String> params = Map.of("start", "0");
        long[] result = ApiHandler.parseTimeRange(params);
        assertNotNull(result);
        long after = System.currentTimeMillis();
        // end should be approximately now
        assertTrue(result[1] >= before && result[1] <= after + 100,
            "Expected end close to now but got " + result[1]);
    }

    @Test
    void parseTimeRange_emptyParams_usesDefaults() {
        long before = System.currentTimeMillis();
        Map<String, String> params = Map.of();
        long[] result = ApiHandler.parseTimeRange(params);
        assertNotNull(result);
        long after = System.currentTimeMillis();
        // end should be approximately now
        assertTrue(result[1] >= before && result[1] <= after + 100);
        // start should be ~15 minutes before end
        assertEquals(result[1] - ApiHandler.DEFAULT_WINDOW_MS, result[0],
            1_000L /* delta tolerance */);
    }
}
