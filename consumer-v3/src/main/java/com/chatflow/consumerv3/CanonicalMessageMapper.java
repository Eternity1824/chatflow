package com.chatflow.consumerv3;

import com.chatflow.protocol.proto.QueueChatMessage;
import com.google.protobuf.InvalidProtocolBufferException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Maps between wire-format bytes (Protobuf {@link QueueChatMessage}) and
 * the DynamoDB-oriented {@link CanonicalMessageRecord}.
 *
 * <p>Thread-safe: stateless, can be shared across virtual threads.
 */
public class CanonicalMessageMapper {

    /**
     * Decode a {@link QueueEnvelope}'s raw Protobuf bytes into a
     * {@link CanonicalMessageRecord} ready for DynamoDB persistence.
     *
     * @throws InvalidProtocolBufferException if the bytes are not a valid
     *         {@code QueueChatMessage} Protobuf message
     * @throws IllegalArgumentException       if required fields are blank
     *         (e.g. {@code messageId})
     */
    public CanonicalMessageRecord fromEnvelope(QueueEnvelope envelope)
            throws InvalidProtocolBufferException {
        QueueChatMessage proto = QueueChatMessage.parseFrom(envelope.getPayload());
        CanonicalMessageRecord record = fromProto(proto);
        // Carry the AMQP delivery tag so AckCoordinator can correlate results
        record.setDeliveryTag(envelope.getDeliveryTag());
        return record;
    }

    /**
     * Map an already-decoded {@link QueueChatMessage} proto to a record.
     *
     * @throws IllegalArgumentException if {@code messageId} or {@code roomId}
     *         is blank — these are non-nullable primary/partition keys
     */
    public CanonicalMessageRecord fromProto(QueueChatMessage proto) {
        String messageId = proto.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is blank — cannot persist without PK");
        }
        String roomId = proto.getRoomId();
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId is blank for messageId=" + messageId);
        }

        long eventTs   = parseTimestamp(proto.getTimestamp());
        String dayBucket = toDayBucket(eventTs);
        long ingestedAt  = System.currentTimeMillis();

        return new CanonicalMessageRecord(
            messageId,
            roomId,
            nullToEmpty(proto.getUserId()),
            nullToEmpty(proto.getUsername()),
            nullToEmpty(proto.getMessage()),
            proto.getMessageType().name(),     // TEXT / JOIN / LEAVE
            proto.getRoomSequence(),
            eventTs,
            ingestedAt,
            nullToEmpty(proto.getServerId()),
            nullToEmpty(proto.getClientIp()),
            dayBucket,
            0L   // deliveryTag — overwritten by fromEnvelope()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parse the proto {@code timestamp} string (epoch-ms as String) into a
     * {@code long}. Falls back to current system time on parse failure so that
     * mapping never throws for this field — a warning is the appropriate action
     * and the caller can log it separately if needed.
     */
    static long parseTimestamp(String ts) {
        if (ts == null || ts.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            // Non-numeric format: return current time as safe fallback
            return System.currentTimeMillis();
        }
    }

    /**
     * Compute UTC day bucket string "YYYY-MM-DD" from epoch-ms.
     * Used for time-range queries and future TTL-based table cleanup.
     */
    static String toDayBucket(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)
                        .toString();   // ISO-8601: "2024-01-15"
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
