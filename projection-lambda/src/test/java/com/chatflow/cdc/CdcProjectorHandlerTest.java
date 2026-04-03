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
 * Unit tests for {@link CdcProjectorHandler#processEvent} failure behaviour.
 *
 * Injected fakes avoid triggering the static initialiser (which requires AWS
 * env vars / network access).
 */
class CdcProjectorHandlerTest {

    // ---- Helpers -------------------------------------------------------------

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

    private static Map<String, AttributeValue> missingMessageId() {
        Map<String, AttributeValue> img = new HashMap<>();
        putS(img, "roomId", "room-1");
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

    /** Writer that always succeeds. */
    private static ProjectionWriter noopWriter() {
        return new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                return BatchWriteResult.success();
            }
        };
    }

    /** Writer that reports the given messageIds as projection failures. */
    private static ProjectionWriter partialWriter(Set<String> failedIds) {
        return new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                return BatchWriteResult.failed(failedIds);
            }
        };
    }

    /** Writer that always throws. */
    private static ProjectionWriter throwingWriter() {
        return new ProjectionWriter() {
            @Override
            public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
                throw new RuntimeException("DynamoDB unavailable");
            }
        };
    }

    /** SQS publisher that always succeeds. */
    private static SqsPublisher noopPublisher() {
        return new SqsPublisher(null, "https://sqs.fake/queue") {
            @Override
            public Set<String> sendBatch(List<AnalyticsEvent> events) {
                return Set.of();
            }
        };
    }

    /** SQS publisher that reports the given chatflow messageIds as failures. */
    private static SqsPublisher failingPublisher(Set<String> failedIds) {
        return new SqsPublisher(null, "https://sqs.fake/queue") {
            @Override
            public Set<String> sendBatch(List<AnalyticsEvent> events) {
                return failedIds;
            }
        };
    }

    // ---- Basic routing -------------------------------------------------------

    @Test
    void nullEvent_returnsEmptyFailures() {
        StreamsEventResponse resp = CdcProjectorHandler.processEvent(null, null, null);
        assertNotNull(resp);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    @Test
    void emptyRecordList_returnsEmptyFailures() {
        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(List.of()), null, null);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    @Test
    void modifyRecord_skipped_notInFailures() {
        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(List.of(modifyRecord())), null, null);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    // ---- Parse failures ------------------------------------------------------

    @Test
    void parseFail_missingMessageId_appearsInFailures() {
        DynamodbStreamRecord rec = insertRecord("seq-001", missingMessageId());
        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(List.of(rec)), null, null);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-001", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void parseFail_nullNewImage_appearsInFailures() {
        StreamRecord sr = new StreamRecord();
        sr.setSequenceNumber("seq-002");
        sr.setNewImage(null);
        DynamodbStreamRecord rec = new DynamodbStreamRecord();
        rec.setEventName("INSERT");
        rec.setDynamodb(sr);

        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(List.of(rec)), null, null);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-002", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // ---- Projection write failures -------------------------------------------

    @Test
    void projectionThrows_allRecordsInFailures() {
        DynamodbStreamRecord rec = insertRecord("seq-003", validImage("msg-1", "room-1"));
        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(List.of(rec)), throwingWriter(), null);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-003", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void projectionPartialFailure_onlyFailedSeqNumsReturned() {
        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok",   validImage("msg-ok",   "room-1")),
            insertRecord("seq-fail", validImage("msg-fail", "room-1"))
        );

        StreamsEventResponse resp = CdcProjectorHandler.processEvent(
            event(records),
            partialWriter(Set.of("msg-fail")),
            noopPublisher()
        );

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-fail", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // ---- SQS send failures ---------------------------------------------------

    /**
     * Key contract: if projection succeeds but SQS send fails, only the
     * SQS-failed records appear in batchItemFailures. The next Lambda
     * invocation will rerun those records -- the idempotent projection write
     * will be a no-op, and SQS send will be retried.
     */
    @Test
    void projectionSucceeds_sqsSendFails_onlyFailedRecordsInFailures() {
        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok",      validImage("msg-ok",      "room-1")),
            insertRecord("seq-sqsfail", validImage("msg-sqsfail", "room-1"))
        );

        StreamsEventResponse resp = CdcProjectorHandler.processEvent(
            event(records),
            noopWriter(),
            failingPublisher(Set.of("msg-sqsfail"))
        );

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("seq-sqsfail", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void projectionSucceeds_sqsSendAllFail_allRecordsInFailures() {
        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-a", validImage("msg-a", "room-1")),
            insertRecord("seq-b", validImage("msg-b", "room-1"))
        );

        StreamsEventResponse resp = CdcProjectorHandler.processEvent(
            event(records),
            noopWriter(),
            failingPublisher(Set.of("msg-a", "msg-b"))
        );

        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> ids = resp.getBatchItemFailures().stream()
            .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(ids.contains("seq-a"));
        assertTrue(ids.contains("seq-b"));
    }

    /** Compound: projection fails for one record; SQS fails for another; third succeeds. */
    @Test
    void mixedFailures_projectionAndSqs_bothFailuresReturned() {
        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok",       validImage("msg-ok",       "room-1")),
            insertRecord("seq-dynofail", validImage("msg-dynofail", "room-1")),
            insertRecord("seq-sqsfail",  validImage("msg-sqsfail",  "room-1"))
        );

        StreamsEventResponse resp = CdcProjectorHandler.processEvent(
            event(records),
            partialWriter(Set.of("msg-dynofail")),
            failingPublisher(Set.of("msg-sqsfail"))
        );

        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> ids = resp.getBatchItemFailures().stream()
            .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(ids.contains("seq-dynofail"), "projection failure must be in failures");
        assertTrue(ids.contains("seq-sqsfail"),  "SQS failure must be in failures");
        assertFalse(ids.contains("seq-ok"),      "successful record must not be in failures");
    }

    // ---- SQS publisher null (misconfiguration) -------------------------------

    /**
     * When projection succeeds but SQS publisher is null (SQS_ANALYTICS_QUEUE_URL
     * not set), all projected records must appear in batchItemFailures.
     * This forces stream retry rather than silently losing analytics data.
     */
    @Test
    void sqsPublisherNull_projectionSucceeded_recordsMustFail() {
        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-a", validImage("msg-a", "room-1")),
            insertRecord("seq-b", validImage("msg-b", "room-1"))
        );

        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(records), noopWriter(), null);

        assertEquals(2, resp.getBatchItemFailures().size(),
            "null publisher must fail all projected records to prevent analytics loss");
        List<String> ids = resp.getBatchItemFailures().stream()
            .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(ids.contains("seq-a"));
        assertTrue(ids.contains("seq-b"));
    }

    /**
     * Projection partially fails (one record) and publisher is null.
     * The projection-failed record fails for its own reason; the projection-
     * succeeded record also fails because there is no publisher to deliver
     * its analytics event.
     */
    @Test
    void sqsPublisherNull_projectionPartialFailure_allRecordsFail() {
        List<DynamodbStreamRecord> records = List.of(
            insertRecord("seq-ok",   validImage("msg-ok",   "room-1")),
            insertRecord("seq-fail", validImage("msg-fail", "room-1"))
        );

        StreamsEventResponse resp = CdcProjectorHandler.processEvent(
            event(records),
            partialWriter(Set.of("msg-fail")),
            null  // no publisher
        );

        // seq-fail: projection failure
        // seq-ok: projection succeeded but publisher is null -> must also fail
        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> ids = resp.getBatchItemFailures().stream()
            .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(ids.contains("seq-fail"), "projection failure must be in failures");
        assertTrue(ids.contains("seq-ok"),   "null publisher must also fail projected record");
    }

    // ---- Successful record ---------------------------------------------------

    @Test
    void fullySuccessfulRecord_notInFailures() {
        DynamodbStreamRecord rec = insertRecord("seq-ok", validImage("msg-ok", "room-1"));
        StreamsEventResponse resp =
            CdcProjectorHandler.processEvent(event(List.of(rec)), noopWriter(), noopPublisher());

        assertTrue(resp.getBatchItemFailures().isEmpty());
    }
}
