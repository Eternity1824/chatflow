package com.chatflow.serverv2;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * DynamoDB query logic for the three projection tables.
 *
 * <h3>Day-bucket key format</h3>
 * <pre>
 *   room_messages pk  = "roomId#YYYYMMDD"
 *   user_messages pk  = "userId#YYYYMMDD"
 *   sk                = "eventTsMs#messageId"   (13-digit epoch, correct lex order)
 * </pre>
 *
 * <h3>Time-range queries</h3>
 * For each day touched by [startMs, endMs], we issue one DynamoDB Query with
 * the day-bucket pk and an {@code sk BETWEEN :startSk AND :endSk} condition
 * so DynamoDB prunes items outside the time range server-side.  Results are
 * merged and sorted by eventTsMs ascending across all days.
 *
 * <h3>Caching</h3>
 * Historical query windows (endMs older than 30 s) are cached in a bounded
 * Caffeine cache (30 s TTL, max 5 000 entries) to avoid repeated DynamoDB
 * reads for the same immutable time range.
 */
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final int MAX_RESULTS = 5_000;

    /** Queries whose endMs is within this window of now are considered "recent" and not cached. */
    private static final long CACHE_RECENCY_THRESHOLD_MS = 30_000L;

    private final DynamoDbClient dynamo;
    private final String tableRoomMessages;
    private final String tableUserMessages;
    private final String tableUserRooms;
    private final boolean enabled;

    private final Cache<String, List<Map<String, Object>>> messageQueryCache;

    public QueryService(ServerV2PersistenceConfig config) {
        if (config.isDynamoEnabled()) {
            this.dynamo = DynamoDbClient.builder()
                .region(Region.of(config.dynamoRegion))
                .build();
            this.enabled = true;
        } else {
            this.dynamo  = null;
            this.enabled = false;
        }
        this.tableRoomMessages = config.tableRoomMessages;
        this.tableUserMessages = config.tableUserMessages;
        this.tableUserRooms    = config.tableUserRooms;
        this.messageQueryCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();
    }

    /** Package-private constructor for testing without AWS. */
    QueryService(DynamoDbClient dynamo, String tableRoomMessages,
                 String tableUserMessages, String tableUserRooms) {
        this.dynamo             = dynamo;
        this.tableRoomMessages  = tableRoomMessages;
        this.tableUserMessages  = tableUserMessages;
        this.tableUserRooms     = tableUserRooms;
        this.enabled            = dynamo != null;
        this.messageQueryCache  = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .recordStats()
            .build();
    }

    public boolean isEnabled() { return enabled; }

    // ── Public API ────────────────────────────────────────────────────────────

    /** All messages for roomId in [startMs, endMs], sorted by eventTsMs asc. */
    public List<Map<String, Object>> getRoomMessages(String roomId, long startMs, long endMs) {
        if (!enabled) return List.of();
        String cacheKey = cacheKey("room", roomId, startMs, endMs);
        if (isCacheable(endMs)) {
            List<Map<String, Object>> cached = messageQueryCache.getIfPresent(cacheKey);
            if (cached != null) return copyMessages(cached);
        }
        List<Map<String, Object>> results = queryRoomMessagesUncached(roomId, startMs, endMs);
        if (isCacheable(endMs)) {
            messageQueryCache.put(cacheKey, immutableMessages(results));
        }
        return results;
    }

    /** All messages for userId in [startMs, endMs], sorted by eventTsMs asc. */
    public List<Map<String, Object>> getUserMessages(String userId, long startMs, long endMs) {
        if (!enabled) return List.of();
        String cacheKey = cacheKey("user", userId, startMs, endMs);
        if (isCacheable(endMs)) {
            List<Map<String, Object>> cached = messageQueryCache.getIfPresent(cacheKey);
            if (cached != null) return copyMessages(cached);
        }
        List<Map<String, Object>> results = queryUserMessagesUncached(userId, startMs, endMs);
        if (isCacheable(endMs)) {
            messageQueryCache.put(cacheKey, immutableMessages(results));
        }
        return results;
    }

    /** All rooms for userId, sorted by lastActivityTs descending. */
    public List<Map<String, Object>> getUserRooms(String userId) {
        if (!enabled) return List.of();
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest.Builder req = QueryRequest.builder()
                    .tableName(tableUserRooms)
                    .keyConditionExpression("userId = :uid")
                    .expressionAttributeValues(Map.of(":uid", s(userId)));
                if (lastKey != null) req.exclusiveStartKey(lastKey);
                QueryResponse resp = dynamo.query(req.build());
                for (Map<String, AttributeValue> item : resp.items()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId",         str(item, "userId"));
                    row.put("roomId",         str(item, "roomId"));
                    row.put("lastActivityTs", num(item, "lastActivityTs"));
                    row.put("username",       str(item, "username"));
                    rows.add(row);
                }
                lastKey = resp.lastEvaluatedKey().isEmpty() ? null : resp.lastEvaluatedKey();
            } while (lastKey != null);
            rows.sort(Comparator.comparingLong((Map<String, Object> m) ->
                (Long) m.get("lastActivityTs")).reversed());
            return rows;
        } catch (Exception e) {
            log.warn("getUserRooms failed userId={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    // ── Uncached query helpers ────────────────────────────────────────────────

    private List<Map<String, Object>> queryRoomMessagesUncached(String roomId, long startMs, long endMs) {
        List<Map<String, Object>> results = new ArrayList<>();
        int remaining = MAX_RESULTS;
        for (String day : expandDayBuckets(startMs, endMs)) {
            String pk = roomId + "#" + day;
            results.addAll(queryByPk(tableRoomMessages, pk, startMs, endMs, remaining));
            remaining = MAX_RESULTS - results.size();
            if (remaining <= 0) break;
        }
        results.sort(Comparator.comparingLong(m -> (Long) m.get("eventTs")));
        return results.size() > MAX_RESULTS ? results.subList(0, MAX_RESULTS) : results;
    }

    private List<Map<String, Object>> queryUserMessagesUncached(String userId, long startMs, long endMs) {
        List<Map<String, Object>> results = new ArrayList<>();
        int remaining = MAX_RESULTS;
        for (String day : expandDayBuckets(startMs, endMs)) {
            String pk = userId + "#" + day;
            results.addAll(queryByPk(tableUserMessages, pk, startMs, endMs, remaining));
            remaining = MAX_RESULTS - results.size();
            if (remaining <= 0) break;
        }
        results.sort(Comparator.comparingLong(m -> (Long) m.get("eventTs")));
        return results.size() > MAX_RESULTS ? results.subList(0, MAX_RESULTS) : results;
    }

    // ── Internal DynamoDB query ───────────────────────────────────────────────

    /**
     * Query one day-bucket's records from table using sk BETWEEN to limit the
     * time range server-side.  A defensive Java-side eventTsMs check is kept
     * but is no longer the primary range-pruning mechanism.
     */
    private List<Map<String, Object>> queryByPk(String table, String pk,
                                                 long startMs, long endMs, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Map<String, AttributeValue> lastKey = null;
            String startSk = startSortKey(startMs);
            String endSk   = endSortKey(endMs);
            do {
                int rem = limit - out.size();
                if (rem <= 0) break;
                QueryRequest.Builder req = QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("pk = :pk AND sk BETWEEN :startSk AND :endSk")
                    .expressionAttributeValues(Map.of(
                        ":pk",      s(pk),
                        ":startSk", s(startSk),
                        ":endSk",   s(endSk)))
                    .scanIndexForward(true)
                    .limit(rem);
                if (lastKey != null) req.exclusiveStartKey(lastKey);
                QueryResponse resp = dynamo.query(req.build());
                for (Map<String, AttributeValue> item : resp.items()) {
                    long ts = num(item, "eventTsMs");
                    if (ts >= startMs && ts <= endMs) { // defensive check
                        out.add(toMessageMap(item));
                    }
                }
                lastKey = resp.lastEvaluatedKey().isEmpty() ? null : resp.lastEvaluatedKey();
            } while (lastKey != null && out.size() < limit);
        } catch (Exception e) {
            log.warn("queryByPk failed table={} pk={}: {}", table, pk, e.getMessage());
        }
        return out;
    }

    private static Map<String, Object> toMessageMap(Map<String, AttributeValue> item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId",   str(item, "messageId"));
        m.put("roomId",      str(item, "roomId"));
        m.put("userId",      str(item, "userId"));
        m.put("username",    str(item, "username"));
        m.put("message",     str(item, "message"));
        m.put("messageType", str(item, "messageType"));
        m.put("eventTs",     num(item, "eventTsMs"));
        return m;
    }

    // ── Day bucket expansion (package-private for tests) ──────────────────────

    /**
     * Expand [startMs, endMs] into a list of "YYYYMMDD" UTC day strings.
     * E.g., 2026-03-25 23:00 UTC to 2026-03-26 01:00 UTC -> ["20260325","20260326"].
     */
    static List<String> expandDayBuckets(long startMs, long endMs) {
        List<String> days = new ArrayList<>();
        LocalDate start = Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end   = Instant.ofEpochMilli(endMs)  .atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(DateTimeFormatter.BASIC_ISO_DATE.format(d)); // "YYYYMMDD"
        }
        return days;
    }

    // ── Sort-key boundary helpers (package-private for tests) ────────────────

    /** Lower-bound sk for a given timestamp (inclusive). */
    static String startSortKey(long tsMs) { return tsMs + "#"; }

    /** Upper-bound sk for a given timestamp (inclusive of all messageId suffixes). */
    static String endSortKey(long tsMs) { return tsMs + "#\uFFFF"; }

    // ── Cache helpers (package-private for tests) ────────────────────────────

    /** Build a cache key distinguishing query type, id, and time window. */
    static String cacheKey(String type, String id, long startMs, long endMs) {
        return type + ":" + id + ":" + startMs + ":" + endMs;
    }

    /**
     * A query window is cacheable only if endMs is at least
     * {@link #CACHE_RECENCY_THRESHOLD_MS} in the past.
     */
    static boolean isCacheable(long endMs) {
        return endMs <= System.currentTimeMillis() - CACHE_RECENCY_THRESHOLD_MS;
    }

    /** Return an unmodifiable deep-copy suitable for storing in the cache. */
    static List<Map<String, Object>> immutableMessages(List<Map<String, Object>> src) {
        List<Map<String, Object>> copy = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(m)));
        }
        return Collections.unmodifiableList(copy);
    }

    /** Return a mutable deep-copy so callers cannot corrupt cached data. */
    static List<Map<String, Object>> copyMessages(List<Map<String, Object>> src) {
        List<Map<String, Object>> copy = new ArrayList<>(src.size());
        for (Map<String, Object> m : src) {
            copy.add(new LinkedHashMap<>(m));
        }
        return copy;
    }

    /** Expose cache stats for reporting / testing. */
    CacheStats messageQueryCacheStats() {
        return messageQueryCache.stats();
    }

    /** Invalidate all cached query results. */
    void invalidateMessageQueryCache() {
        messageQueryCache.invalidateAll();
    }

    // ── DynamoDB helpers ──────────────────────────────────────────────────────

    private static AttributeValue s(String v) { return AttributeValue.fromS(v != null ? v : ""); }
    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return (v != null && v.s() != null) ? v.s() : "";
    }
    private static long num(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        if (v == null || v.n() == null) return 0L;
        try { return Long.parseLong(v.n()); } catch (NumberFormatException e) { return 0L; }
    }
}
