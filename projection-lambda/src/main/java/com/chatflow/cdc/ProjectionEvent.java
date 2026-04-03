package com.chatflow.cdc;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;

import java.util.Map;

/**
 * Domain object extracted from a DynamoDB Streams {@code NEW_IMAGE} that
 * represents one newly-written canonical message.
 *
 * <p>Field names mirror the canonical table attributes written by
 * {@code consumer-v3 PersistenceWriter.buildItem()}.
 */
public class ProjectionEvent {

    public final String messageId;
    public final String roomId;
    public final String userId;
    public final String username;
    public final String message;
    public final String messageType;
    public final long   roomSequence;

    /** Client-originated event timestamp (epoch-ms). */
    public final long   eventTsMs;

    /** Consumer ingest timestamp (epoch-ms). */
    public final long   ingestedAtMs;

    /** UTC day string "YYYY-MM-DD" from canonical table. */
    public final String dayBucket;

    public ProjectionEvent(String messageId, String roomId, String userId,
                            String username, String message, String messageType,
                            long roomSequence, long eventTsMs, long ingestedAtMs,
                            String dayBucket) {
        this.messageId    = messageId;
        this.roomId       = roomId;
        this.userId       = userId;
        this.username     = username;
        this.message      = message;
        this.messageType  = messageType;
        this.roomSequence = roomSequence;
        this.eventTsMs    = eventTsMs;
        this.ingestedAtMs = ingestedAtMs;
        this.dayBucket    = dayBucket;
    }

    // ── Projection table keys ─────────────────────────────────────────────────

    /**
     * Partition key for {@code room_messages}: {@code "roomId#yyyyMMdd"}.
     * Enables efficient range queries across all messages for a room on a given day.
     */
    public String roomDayKey() {
        return roomId + "#" + compactDay();
    }

    /**
     * Partition key for {@code user_messages}: {@code "userId#yyyyMMdd"}.
     * Enables per-user daily message history queries.
     */
    public String userDayKey() {
        return userId + "#" + compactDay();
    }

    /**
     * Sort key for both message projection tables: {@code "eventTsMs#messageId"}.
     * Millisecond precision with UUID suffix ensures global sort order uniqueness.
     */
    public String sortKey() {
        return eventTsMs + "#" + messageId;
    }

    // ── Redis bucket helpers ──────────────────────────────────────────────────

    /** Floor-minute bucket: {@code eventTsMs / 60_000}. Used as Redis key suffix. */
    public long minuteBucket() { return eventTsMs / 60_000L; }

    /** Floor-second bucket: {@code eventTsMs / 1_000}. Used as Redis key suffix. */
    public long secondBucket() { return eventTsMs / 1_000L; }

    // ── Factory from DynamoDB Streams NEW_IMAGE ───────────────────────────────

    /**
     * Parse a DynamoDB Streams {@code NEW_IMAGE} attribute map into a
     * {@link ProjectionEvent}.
     *
     * @throws IllegalArgumentException if {@code messageId} or {@code roomId} are blank
     */
    public static ProjectionEvent fromNewImage(Map<String, AttributeValue> img) {
        String messageId = s(img, "messageId");
        String roomId    = s(img, "roomId");
        if (messageId.isBlank() || roomId.isBlank()) {
            throw new IllegalArgumentException(
                "Stream record missing required keys: messageId='" + messageId
                + "' roomId='" + roomId + "'");
        }
        return new ProjectionEvent(
            messageId,
            roomId,
            s(img, "userId"),
            s(img, "username"),
            s(img, "message"),
            s(img, "messageType"),
            n(img, "roomSequence"),
            n(img, "eventTs"),
            n(img, "ingestedAt"),
            s(img, "dayBucket")
        );
    }

    // ── Attribute helpers ─────────────────────────────────────────────────────

    private static String s(Map<String, AttributeValue> img, String key) {
        AttributeValue v = img.get(key);
        return (v != null && v.getS() != null) ? v.getS() : "";
    }

    private static long n(Map<String, AttributeValue> img, String key) {
        AttributeValue v = img.get(key);
        if (v == null || v.getN() == null) return 0L;
        try { return Long.parseLong(v.getN()); }
        catch (NumberFormatException e) { return 0L; }
    }

    /** Convert "YYYY-MM-DD" dayBucket to compact "YYYYMMDD" for composite keys. */
    private String compactDay() {
        return dayBucket.replace("-", "");
    }

    @Override
    public String toString() {
        return "ProjectionEvent{messageId='" + messageId
            + "', roomId='" + roomId + "', userId='" + userId
            + "', eventTsMs=" + eventTsMs + ", dayBucket='" + dayBucket + "'}";
    }
}
