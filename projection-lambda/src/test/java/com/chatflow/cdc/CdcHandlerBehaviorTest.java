package com.chatflow.cdc;

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CdcLambdaHandler#processEvent} failure behaviour.
 *
 * <p>Tests use {@link CdcLambdaHandler#processEvent} directly so they never
 * trigger the {@code static} initialiser (which requires AWS env vars).
 * Writer and analytics are passed as {@code null}; every test is designed so
 * the failure occurs before those objects are used (e.g. parse failure), or
 * uses a fake that throws on demand.
 */
class CdcHandlerBehaviorTest {

    // Helpers

    private static DynamodbStreamRecord insertRecord(String seqNum,
                                                      Map<String, AttributeValue> image) {
        StreamRecord sr = new StreamRecord();
        sr.setSequenceNumber(seqNum);
        sr.setNewImage(image);

        DynamodbStreamRecord rec = new DynamodbStreamRecord();
        rec.setEventName("INSERT");
        rec.setEventID("evt-" + seqNum);
        rec.setDynamodb(sr);
        return rec;
    }

    private static DynamodbStreamRecord modifyRecord() {
        DynamodbStreamRecord rec = new DynamodbStreamRecord();
        rec.setEventName("MODIFY");
        return rec;
    }

    private static Map<String, AttributeValue> validImage(String messageId, String roomId) {
        Map<String, AttributeValue> img = new HashMap<>();
        putS(img, "messageId", messageId);
        putS(img, "roomId",    roomId);
        return img;
    }

    private static Map<String, AttributeValue> missingMessageId(String roomId) {
        Map<String, AttributeValue> img = new HashMap<>();
        putS(img, "roomId", roomId);
        return img;
    }

    private static void putS(Map<String, AttributeValue> img, String key, String val) {
        AttributeValue av = new AttributeValue();
        av.setS(val);
        img.put(key, av);
    }

    private static DynamodbEvent event(List<DynamodbStreamRecord> records) {
        DynamodbEvent ev = new DynamodbEvent();
        ev.setRecords(records);
        return ev;
    }

    // Empty / null events

    @Test
    void nullEvent_returnsEmptyFailures() {
        StreamsEventResponse resp = CdcLambdaHandler.processEvent(null, null, null);
        assertNotNull(resp);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    @Test
    void emptyRecordList_returnsEmptyFailures() {
        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(List.of()), null, null);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    // Non-INSERT events are skipped, not failures

    @Test
    void modifyRecord_skipped_notInFailures() {
        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(List.of(modifyRecord())), null, null);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    // Parse failure -> batchItemFailures

    @Test
    void insertWithMissingMessageId_appearsInFailures() {
        // ProjectionEvent.fromNewImage throws IllegalArgumentException -> failure
        DynamodbStreamRecord rec = insertRecord("seq-001", missingMessageId("room-1"));
        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(List.of(rec)), null, null);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-001", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void nullNewImage_appearsInFailures() {
        StreamRecord sr = new StreamRecord();
        sr.setSequenceNumber("seq-002");
        sr.setNewImage(null);  // null image on INSERT

        DynamodbStreamRecord rec = new DynamodbStreamRecord();
        rec.setEventName("INSERT");
        rec.setDynamodb(sr);

        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(List.of(rec)), null, null);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-002", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // Writer failure -> batchItemFailures

    @Test
    void writerThrows_recordAppearsInFailures() {
        // ProjectionWriter fake that always throws (uses protected no-arg ctor)
        ProjectionWriter throwingWriter = new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                throw new RuntimeException("DynamoDB unavailable");
            }
        };

        DynamodbStreamRecord rec = insertRecord("seq-003", validImage("msg-1", "room-1"));
        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(List.of(rec)), throwingWriter, disabledAnalytics());

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-003", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // Mixed batch: one success (parse), one failure

    @Test
    void mixedBatch_onlyFailedRecordInResponse() {
        // seq-ok: valid image but writer throws (seq-ok -> failure)
        // seq-bad: missing messageId (parse error -> failure)
        ProjectionWriter throwingWriter = new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                throw new RuntimeException("simulated failure");
            }
        };

        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok",  validImage("msg-ok", "room-1")),
            insertRecord("seq-bad", missingMessageId("room-1"))
        );

        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(records), throwingWriter, disabledAnalytics());

        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> ids = resp.getBatchItemFailures().stream()
            .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(ids.contains("seq-ok"));
        assertTrue(ids.contains("seq-bad"));
    }

    // Successful record is NOT in batchItemFailures

    @Test
    void successfulRecord_notInFailures() {
        ProjectionWriter noopWriter = new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                return BatchWriteResult.success();
            }
        };

        DynamodbStreamRecord rec = insertRecord("seq-ok", validImage("msg-1", "room-1"));
        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(List.of(rec)), noopWriter, disabledAnalytics());

        assertTrue(resp.getBatchItemFailures().isEmpty(),
            "successful record must not appear in batchItemFailures");
    }

    @Test
    void batchWriterPartialFailure_marksOnlyFailedSequenceNumbers() {
        ProjectionWriter partiallyFailingWriter = new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                return BatchWriteResult.failed(Set.of("msg-fail"));
            }
        };

        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok", validImage("msg-ok", "room-1")),
            insertRecord("seq-fail", validImage("msg-fail", "room-1"))
        );

        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(records), partiallyFailingWriter, disabledAnalytics());

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-fail", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void analyticsBatchFailure_marksOnlyProjectionSuccessfulSequenceNumbers() {
        ProjectionWriter writer = new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                return BatchWriteResult.failed(Set.of("msg-projection-fail"));
            }
        };

        RedisAnalytics failingAnalytics = new RedisAnalytics(CdcConfig.fromEnv()) {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public long recordBatchAnalytics(List<ProjectionEvent> events) {
                throw new RuntimeException("Redis unavailable");
            }
        };

        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok", validImage("msg-ok", "room-1")),
            insertRecord("seq-projection-fail", validImage("msg-projection-fail", "room-1"))
        );

        StreamsEventResponse resp =
            CdcLambdaHandler.processEvent(event(records), writer, failingAnalytics);

        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> ids = resp.getBatchItemFailures().stream()
            .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(ids.contains("seq-ok"));
        assertTrue(ids.contains("seq-projection-fail"));
    }

    // Test doubles

    /** RedisAnalytics with Redis disabled - safe to use in unit tests. */
    private static RedisAnalytics disabledAnalytics() {
        // CdcConfig without REDIS_ENDPOINT -> isRedisEnabled() = false, no connection
        return new RedisAnalytics(CdcConfig.fromEnv());
    }
}
