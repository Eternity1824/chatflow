package com.chatflow.cdc;

/**
 * Minimal event payload exchanged on the analytics SQS queue between
 * Lambda A (CDC projector) and Lambda B (Redis analytics).
 *
 * Only the five fields required by {@link RedisAnalytics} are included.
 * Jackson default-visibility serialization is used (public fields).
 */
public class AnalyticsEvent {

    /** Chatflow message UUID. Used for Redis deduplification key. */
    public String messageId;

    /** Sending user's ID. Used for active_users and top_users Redis keys. */
    public String userId;

    /** Room ID. Used for top_rooms Redis key. */
    public String roomId;

    /** Client-originated event timestamp (epoch-ms). Drives minute/second buckets. */
    public long eventTsMs;

    /** Consumer ingest timestamp (epoch-ms). Used for projection health markers. */
    public long ingestedAtMs;

    /** Default constructor for Jackson deserialization. */
    public AnalyticsEvent() {}

    public AnalyticsEvent(String messageId, String userId, String roomId,
                           long eventTsMs, long ingestedAtMs) {
        this.messageId    = messageId;
        this.userId       = userId;
        this.roomId       = roomId;
        this.eventTsMs    = eventTsMs;
        this.ingestedAtMs = ingestedAtMs;
    }

    /** Extract the analytics-relevant fields from a full projection event. */
    public static AnalyticsEvent from(ProjectionEvent pe) {
        return new AnalyticsEvent(pe.messageId, pe.userId, pe.roomId,
                                  pe.eventTsMs, pe.ingestedAtMs);
    }

    /**
     * Produce a {@link ProjectionEvent} with only the analytics-relevant fields
     * populated.  Non-analytics fields (username, message, messageType,
     * roomSequence, dayBucket) are left as empty/zero; they are not referenced
     * by {@link RedisAnalytics}.
     */
    public ProjectionEvent toProjectionEvent() {
        return new ProjectionEvent(
            messageId, roomId, userId, "", "", "", 0L, eventTsMs, ingestedAtMs, "");
    }

    @Override
    public String toString() {
        return "AnalyticsEvent{messageId='" + messageId
            + "', userId='" + userId
            + "', roomId='" + roomId
            + "', eventTsMs=" + eventTsMs
            + ", ingestedAtMs=" + ingestedAtMs + "}";
    }
}
