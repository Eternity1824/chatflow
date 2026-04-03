package com.chatflow.cdc;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the static item-building methods in {@link ProjectionWriter} without
 * requiring a live DynamoDB connection.
 */
class ProjectionWriterMappingTest {

    private static final ProjectionEvent PE = new ProjectionEvent(
        "msg-001", "room-3", "user-7", "bob", "hi there", "TEXT",
        42L, 1_700_100_000_000L, 1_700_100_001_000L, "2023-11-15");

    // room_messages item

    @Test
    void roomMessagesItem_pk_isRoomDayKey() {
        Map<String, AttributeValue> item = ProjectionWriter.roomMessagesItem(PE);
        assertEquals(PE.roomDayKey(), item.get("pk").s());
    }

    @Test
    void roomMessagesItem_sk_isSortKey() {
        Map<String, AttributeValue> item = ProjectionWriter.roomMessagesItem(PE);
        assertEquals(PE.sortKey(), item.get("sk").s());
    }

    @Test
    void roomMessagesItem_containsRequiredAttributes() {
        Map<String, AttributeValue> item = ProjectionWriter.roomMessagesItem(PE);
        assertNotNull(item.get("messageId"));
        assertNotNull(item.get("roomId"));
        assertNotNull(item.get("userId"));
        assertNotNull(item.get("username"));
        assertNotNull(item.get("message"));
        assertNotNull(item.get("messageType"));
        assertNotNull(item.get("roomSequence"));
        assertNotNull(item.get("eventTsMs"));
        assertNotNull(item.get("ingestedAtMs"));
    }

    @Test
    void roomMessagesItem_numericFields_storedAsNumbers() {
        Map<String, AttributeValue> item = ProjectionWriter.roomMessagesItem(PE);
        assertEquals("42",               item.get("roomSequence").n());
        assertEquals("1700100000000",    item.get("eventTsMs").n());
        assertEquals("1700100001000",    item.get("ingestedAtMs").n());
    }

    // user_messages item

    @Test
    void userMessagesItem_pk_isUserDayKey() {
        Map<String, AttributeValue> item = ProjectionWriter.userMessagesItem(PE);
        assertEquals(PE.userDayKey(), item.get("pk").s());
    }

    @Test
    void userMessagesItem_sk_isSortKey() {
        Map<String, AttributeValue> item = ProjectionWriter.userMessagesItem(PE);
        assertEquals(PE.sortKey(), item.get("sk").s());
    }

    @Test
    void userMessagesItem_containsRequiredAttributes() {
        Map<String, AttributeValue> item = ProjectionWriter.userMessagesItem(PE);
        assertNotNull(item.get("pk"));
        assertNotNull(item.get("sk"));
        assertNotNull(item.get("messageId"));
        assertNotNull(item.get("userId"));
        assertNotNull(item.get("roomId"));
        assertNotNull(item.get("eventTsMs"));
        assertNotNull(item.get("ingestedAtMs"));
    }

    // Key format sanity

    @Test
    void sortKey_formatIsTimestampHashMessageId() {
        // "eventTsMs#messageId"
        assertEquals("1700100000000#msg-001", PE.sortKey());
    }

    @Test
    void roomDayKey_formatIsRoomIdHashCompactDate() {
        // dayBucket "2023-11-15" -> "20231115"
        assertEquals("room-3#20231115", PE.roomDayKey());
    }

    @Test
    void userDayKey_formatIsUserIdHashCompactDate() {
        assertEquals("user-7#20231115", PE.userDayKey());
    }
}
