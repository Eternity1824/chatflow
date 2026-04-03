package com.chatflow.serverv2;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Redis analytics queries for the API layer.
 *
 * <h3>Key schema (mirrors RedisAnalytics in projection-lambda)</h3>
 * <pre>
 *   active_users:minute:{min}    SADD userId
 *   top_users:minute:{min}       ZINCRBY userId
 *   top_rooms:minute:{min}       ZINCRBY roomId
 *   messages:second:{sec}        INCR
 *   messages:minute:{min}        INCR
 * </pre>
 *
 * <h3>Union queries</h3>
 * Multi-minute ranges use SUNIONSTORE / ZUNIONSTORE with a short-lived temp key
 * (TTL=30s, UUID suffix to avoid collisions) to compute union size atomically.
 */
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final int TEMP_KEY_TTL_SECONDS = 30;

    private final boolean                          enabled;
    private final RedisClient                      client;
    private final StatefulRedisConnection<String,String> conn;
    private final RedisCommands<String, String>    sync;

    public AnalyticsService(ServerV2PersistenceConfig config) {
        if (config.isRedisEnabled()) {
            RedisURI uri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .build();
            this.client  = RedisClient.create(uri);
            this.conn    = client.connect();
            this.sync    = conn.sync();
            this.enabled = true;
            log.info("AnalyticsService connected to Redis {}:{}", config.redisHost, config.redisPort);
        } else {
            this.client  = null;
            this.conn    = null;
            this.sync    = null;
            this.enabled = false;
            log.info("AnalyticsService: Redis not configured — analytics will return empty data");
        }
    }

    public boolean isEnabled() { return enabled; }

    /** Package-private: Redis commands, for sharing with ProjectionHealthService. */
    RedisCommands<String, String> sync() { return sync; }

    // ── Active users ──────────────────────────────────────────────────────────

    /**
     * Count distinct active users across all minute buckets in [startMs, endMs].
     * Uses SUNIONSTORE into a temp key with a short TTL.
     */
    public long getActiveUserCount(long startMs, long endMs) {
        if (!enabled) return 0L;
        try {
            String[] keys = minuteKeys("active_users:minute:", startMs, endMs);
            if (keys.length == 0) return 0L;
            String tmp = tempKey("active_users_union");
            sync.sunionstore(tmp, keys);
            sync.expire(tmp, TEMP_KEY_TTL_SECONDS);
            long count = sync.scard(tmp);
            sync.del(tmp);
            return count;
        } catch (Exception e) {
            log.warn("getActiveUserCount failed: {}", e.getMessage());
            return 0L;
        }
    }

    // ── Message counters ──────────────────────────────────────────────────────

    public List<Map<String, Object>> getMessagesPerMinute(long startMs, long endMs) {
        if (!enabled) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        long minStart = startMs / 60_000L;
        long minEnd   = endMs   / 60_000L;
        try {
            for (long b = minStart; b <= minEnd; b++) {
                String val = sync.get("messages:minute:" + b);
                long count = val != null ? Long.parseLong(val) : 0L;
                out.add(Map.of("bucket", b, "count", count));
            }
        } catch (Exception e) {
            log.warn("getMessagesPerMinute failed: {}", e.getMessage());
        }
        return out;
    }

    public List<Map<String, Object>> getMessagesPerSecond(long startMs, long endMs) {
        if (!enabled) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        long secStart = startMs / 1_000L;
        long secEnd   = endMs   / 1_000L;
        // Cap to avoid huge ranges
        long cap = Math.min(secEnd - secStart + 1, 300);
        try {
            for (long b = secStart; b < secStart + cap; b++) {
                String val = sync.get("messages:second:" + b);
                long count = val != null ? Long.parseLong(val) : 0L;
                out.add(Map.of("bucket", b, "count", count));
            }
        } catch (Exception e) {
            log.warn("getMessagesPerSecond failed: {}", e.getMessage());
        }
        return out;
    }

    // ── Top users / rooms ─────────────────────────────────────────────────────

    public List<Map<String, Object>> getTopUsers(long startMs, long endMs, int topN) {
        return getTopFromZsets("top_users:minute:", startMs, endMs, topN, "userId");
    }

    public List<Map<String, Object>> getTopRooms(long startMs, long endMs, int topN) {
        return getTopFromZsets("top_rooms:minute:", startMs, endMs, topN, "roomId");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getTopFromZsets(
            String prefix, long startMs, long endMs, int topN, String idField) {
        if (!enabled) return List.of();
        try {
            String[] keys = minuteKeys(prefix, startMs, endMs);
            if (keys.length == 0) return List.of();
            String tmp = tempKey("zunion");
            sync.zunionstore(tmp, keys);
            sync.expire(tmp, TEMP_KEY_TTL_SECONDS);
            List<ScoredValue<String>> top = sync.zrevrangeWithScores(tmp, 0, topN - 1);
            sync.del(tmp);
            List<Map<String, Object>> out = new ArrayList<>(top.size());
            for (ScoredValue<String> sv : top) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(idField, sv.getValue());
                m.put("messageCount", (long) sv.getScore());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("getTop{} failed: {}", idField, e.getMessage());
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build Redis keys for each minute bucket in [startMs, endMs]. */
    static String[] minuteKeys(String prefix, long startMs, long endMs) {
        long minStart = startMs / 60_000L;
        long minEnd   = endMs   / 60_000L;
        List<String> keys = new ArrayList<>();
        for (long b = minStart; b <= minEnd; b++) {
            keys.add(prefix + b);
        }
        return keys.toArray(new String[0]);
    }

    /** Generate a temp key with UUID suffix to avoid collisions. */
    static String tempKey(String purpose) {
        return "tmp:" + purpose + ":" + UUID.randomUUID().toString().replace("-", "");
    }
}
