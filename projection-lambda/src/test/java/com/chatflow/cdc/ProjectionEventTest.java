package com.chatflow.cdc;

import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionEventTest {

    // Fixture

    private static Map<String, AttributeValue> sampleImage() {
        Map<String, AttributeValue> img = new HashMap<>();
        putS(img, "messageId",   "msg-123");
        putS(img, "roomId",      "room-5");
        putS(img, "userId",      "user-42");
        putS(img, "username",    "alice");
        putS(img, "message",     "hello world");
        putS(img, "messageType", "TEXT");
        putN(img, "roomSequence",  "7");
        putN(img, "eventTs",       "1700000000000");
        putN(img, "ingestedAt",    "1700000001000");
        putS(img, "dayBucket",   "2023-11-14");
        return img;
    }

    private static void putS(Map<String, AttributeValue> img, String key, String val) {
        AttributeValue av = new AttributeValue();
        av.setS(val);
        img.put(key, av);
    }

    private static void putN(Map<String, AttributeValue> img, String key, String num) {
        AttributeValue av = new AttributeValue();
        av.setN(num);
        img.put(key, av);
    }

    // fromNewImage field mapping

    @Test
    void fromNewImage_mapsAllStringFields() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        assertEquals("msg-123",      pe.messageId);
        assertEquals("room-5",       pe.roomId);
        assertEquals("user-42",      pe.userId);
        assertEquals("alice",        pe.username);
        assertEquals("hello world",  pe.message);
        assertEquals("TEXT",         pe.messageType);
        assertEquals("2023-11-14",   pe.dayBucket);
    }

    @Test
    void fromNewImage_mapsNumericFields() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        assertEquals(7L,              pe.roomSequence);
        assertEquals(1700000000000L,  pe.eventTsMs);
        assertEquals(1700000001000L,  pe.ingestedAtMs);
    }

    // Key generation

    @Test
    void roomDayKey_compositeFormat() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        // dayBucket "2023-11-14" -> compact "20231114"
        assertEquals("room-5#20231114", pe.roomDayKey());
    }

    @Test
    void userDayKey_compositeFormat() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        assertEquals("user-42#20231114", pe.userDayKey());
    }

    @Test
    void sortKey_compositeFormat() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        assertEquals("1700000000000#msg-123", pe.sortKey());
    }

    // Bucket helpers

    @Test
    void minuteBucket_floorsCorrectly() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        assertEquals(1700000000000L / 60_000L, pe.minuteBucket());
    }

    @Test
    void secondBucket_floorsCorrectly() {
        ProjectionEvent pe = ProjectionEvent.fromNewImage(sampleImage());
        assertEquals(1_700_000_000L, pe.secondBucket());
    }

    // Validation

    @Test
    void fromNewImage_missingMessageId_throws() {
        Map<String, AttributeValue> img = sampleImage();
        img.remove("messageId");
        assertThrows(IllegalArgumentException.class,
            () -> ProjectionEvent.fromNewImage(img));
    }

    @Test
    void fromNewImage_missingRoomId_throws() {
        Map<String, AttributeValue> img = sampleImage();
        img.remove("roomId");
        assertThrows(IllegalArgumentException.class,
            () -> ProjectionEvent.fromNewImage(img));
    }

    @Test
    void fromNewImage_missingOptionalFields_usesDefaults() {
        Map<String, AttributeValue> img = new HashMap<>();
        putS(img, "messageId", "m1");
        putS(img, "roomId",    "r1");
        // All other fields absent

        ProjectionEvent pe = ProjectionEvent.fromNewImage(img);
        assertEquals("",  pe.userId);
        assertEquals("",  pe.username);
        assertEquals(0L,  pe.eventTsMs);
        assertEquals(0L,  pe.ingestedAtMs);
        assertEquals("",  pe.dayBucket);
    }
}
