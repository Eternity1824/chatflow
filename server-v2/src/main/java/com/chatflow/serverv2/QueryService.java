package com.chatflow.serverv2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
 * the full-day pk.  Records are filtered to [startMs, endMs] in Java, then
 * merged and sorted by eventTsMs ascending across all days.
 */
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final int MAX_RESULTS = 5_000;

    private final DynamoDbClient dynamo;
    private final String tableRoomMessages;
    private final String tableUserMessages;
    private final String tableUserRooms;
    private final boolean enabled;

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
    }

    public boolean isEnabled() { return enabled; }

    // ── Public API ────────────────────────────────────────────────────────────

    /** All messages for roomId in [startMs, endMs], sorted by eventTsMs asc. */
    public List<Map<String, Object>> getRoomMessages(String roomId, long startMs, long endMs) {
        if (!enabled) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        for (String day : expandDayBuckets(startMs, endMs)) {
            String pk = roomId + "#" + day;
            results.addAll(queryByPk(tableRoomMessages, pk, startMs, endMs));
            if (results.size() >= MAX_RESULTS) break;
        }
        results.sort(Comparator.comparingLong(m -> (Long) m.get("eventTs")));
        return results.size() > MAX_RESULTS ? results.subList(0, MAX_RESULTS) : results;
    }

    /** All messages for userId in [startMs, endMs], sorted by eventTsMs asc. */
    public List<Map<String, Object>> getUserMessages(String userId, long startMs, long endMs) {
        if (!enabled) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        for (String day : expandDayBuckets(startMs, endMs)) {
            String pk = userId + "#" + day;
            results.addAll(queryByPk(tableUserMessages, pk, startMs, endMs));
            if (results.size() >= MAX_RESULTS) break;
        }
        results.sort(Comparator.comparingLong(m -> (Long) m.get("eventTs")));
        return results.size() > MAX_RESULTS ? results.subList(0, MAX_RESULTS) : results;
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

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Query one day-bucket's records from table, filter to [startMs, endMs]. */
    private List<Map<String, Object>> queryByPk(String table, String pk, long startMs, long endMs) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest.Builder req = QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("pk = :pk")
                    .expressionAttributeValues(Map.of(":pk", s(pk)))
                    .scanIndexForward(true);
                if (lastKey != null) req.exclusiveStartKey(lastKey);
                QueryResponse resp = dynamo.query(req.build());
                for (Map<String, AttributeValue> item : resp.items()) {
                    long ts = num(item, "eventTsMs");
                    if (ts >= startMs && ts <= endMs) {
                        out.add(toMessageMap(item));
                    }
                }
                lastKey = resp.lastEvaluatedKey().isEmpty() ? null : resp.lastEvaluatedKey();
            } while (lastKey != null && out.size() < MAX_RESULTS);
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
     * E.g., 2026-03-25 23:00 UTC to 2026-03-26 01:00 UTC → ["20260325","20260326"].
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
