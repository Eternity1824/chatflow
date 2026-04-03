package com.chatflow.consumerv3;

import com.chatflow.protocol.proto.MessageType;
import com.chatflow.protocol.proto.QueueChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalMessageMapperTest {

    private CanonicalMessageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CanonicalMessageMapper();
    }

    // ── fromProto ──────────────────────────────────────────────────────────────

    @Test
    void fromProto_allFields_mappedCorrectly() {
        long ts = 1_700_000_000_000L;
        QueueChatMessage proto = QueueChatMessage.newBuilder()
            .setMessageId("msg-001")
            .setRoomId("room.5")
            .setUserId("user-42")
            .setUsername("Alice")
            .setMessage("Hello!")
            .setTimestamp(Long.toString(ts))
            .setMessageType(MessageType.TEXT)
            .setRoomSequence(7L)
            .setServerId("server-1")
            .setClientIp("10.0.0.1")
            .build();

        CanonicalMessageRecord record = mapper.fromProto(proto);

        assertEquals("msg-001",   record.getMessageId());
        assertEquals("room.5",    record.getRoomId());
        assertEquals("user-42",   record.getUserId());
        assertEquals("Alice",     record.getUsername());
        assertEquals("Hello!",    record.getMessage());
        assertEquals("TEXT",      record.getMessageType());
        assertEquals(7L,          record.getRoomSequence());
        assertEquals(ts,          record.getEventTs());
        assertEquals("server-1",  record.getServerId());
        assertEquals("10.0.0.1",  record.getClientIp());
        assertEquals("2023-11-14", record.getDayBucket());  // UTC day for epoch 1700000000000
        assertTrue(record.getIngestedAt() > 0, "ingestedAt must be positive");
        assertTrue(record.getIngestedAt() >= ts, "ingestedAt must be >= eventTs");
    }

    @Test
    void fromProto_blankMessageId_throws() {
        QueueChatMessage proto = QueueChatMessage.newBuilder()
            .setMessageId("")
            .setRoomId("room.1")
            .build();

        assertThrows(IllegalArgumentException.class, () -> mapper.fromProto(proto));
    }

    @Test
    void fromProto_blankRoomId_throws() {
        QueueChatMessage proto = QueueChatMessage.newBuilder()
            .setMessageId("msg-002")
            .setRoomId("")
            .build();

        assertThrows(IllegalArgumentException.class, () -> mapper.fromProto(proto));
    }

    @Test
    void fromProto_joinMessageType_mapped() {
        QueueChatMessage proto = QueueChatMessage.newBuilder()
            .setMessageId("msg-003")
            .setRoomId("room.1")
            .setMessageType(MessageType.JOIN)
            .setTimestamp("0")
            .build();

        CanonicalMessageRecord record = mapper.fromProto(proto);
        assertEquals("JOIN", record.getMessageType());
    }

    @Test
    void fromProto_ingestedAt_setByConsumer_notClientClock() {
        // ingestedAt should be >= eventTs (consumer sets it at write time)
        long eventTs = 1_000_000L;
        QueueChatMessage proto = QueueChatMessage.newBuilder()
            .setMessageId("msg-004")
            .setRoomId("room.1")
            .setTimestamp(Long.toString(eventTs))
            .build();

        CanonicalMessageRecord record = mapper.fromProto(proto);
        assertEquals(eventTs, record.getEventTs());
        assertNotEquals(eventTs, record.getIngestedAt(),
            "ingestedAt must differ from eventTs — it is set by the consumer");
        assertTrue(record.getIngestedAt() > eventTs);
    }

    // ── fromEnvelope ──────────────────────────────────────────────────────────

    @Test
    void fromEnvelope_validProtobuf_deliveryTagPropagated() throws Exception {
        long deliveryTag = 99L;
        QueueChatMessage proto = QueueChatMessage.newBuilder()
            .setMessageId("msg-005")
            .setRoomId("room.2")
            .setTimestamp("1700000000000")
            .build();

        byte[] payload = proto.toByteArray();
        QueueEnvelope env = new QueueEnvelope(payload, deliveryTag, "room.2", "room.2");

        CanonicalMessageRecord record = mapper.fromEnvelope(env);

        assertEquals("msg-005", record.getMessageId());
        assertEquals(deliveryTag, record.getDeliveryTag());
    }

    @Test
    void fromEnvelope_invalidBytes_throwsProtobufException() {
        QueueEnvelope env = new QueueEnvelope(new byte[]{0x00, (byte) 0xFF}, 1L, "room.1", "room.1");
        // Malformed protobuf — should throw InvalidProtocolBufferException
        assertThrows(Exception.class, () -> mapper.fromEnvelope(env));
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    @Test
    void parseTimestamp_validEpochMs_parsed() {
        assertEquals(1_700_000_000_000L, CanonicalMessageMapper.parseTimestamp("1700000000000"));
    }

    @Test
    void parseTimestamp_blank_returnsCurrent() {
        long before = System.currentTimeMillis();
        long result = CanonicalMessageMapper.parseTimestamp("");
        long after  = System.currentTimeMillis();
        assertTrue(result >= before && result <= after);
    }

    @Test
    void parseTimestamp_nonNumeric_returnsCurrent() {
        long before = System.currentTimeMillis();
        long result = CanonicalMessageMapper.parseTimestamp("not-a-number");
        long after  = System.currentTimeMillis();
        assertTrue(result >= before && result <= after);
    }

    @Test
    void toDayBucket_knownEpoch_correctDate() {
        // 2023-11-14T22:13:20 UTC → day bucket 2023-11-14
        assertEquals("2023-11-14", CanonicalMessageMapper.toDayBucket(1_700_000_000_000L));
    }
}
