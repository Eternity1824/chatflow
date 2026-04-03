package com.chatflow.cdc;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RedisAnalyticsHandler#processEvent}.
 *
 * Tests verify:
 * <ul>
 *   <li>Correct batch aggregation args forwarded to {@link RedisAnalytics}.</li>
 *   <li>Duplicate messages (same chatflow messageId) do not double-count via
 *       the Lua dedupe path.</li>
 *   <li>Redis failure marks all parsed SQS messages as failures (full retry).</li>
 *   <li>JSON parse failure marks only the bad message, not the rest.</li>
 *   <li>Redis disabled marks all parsed SQS messages as failures (not consumed).</li>
 * </ul>
 */
class RedisAnalyticsHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- SQS event builder helpers ------------------------------------------

    private static SQSEvent sqsEvent(List<SQSMessage> msgs) {
        SQSEvent ev = new SQSEvent();
        ev.setRecords(msgs);
        return ev;
    }

    private static SQSMessage sqsMessage(String sqsId, String body) {
        SQSMessage msg = new SQSMessage();
        msg.setMessageId(sqsId);
        msg.setBody(body);
        return msg;
    }

    private static SQSMessage analyticsMessage(String sqsId,
                                                String chatflowId, String userId,
                                                String roomId, long eventTs, long ingestedAt)
            throws Exception {
        AnalyticsEvent ae = new AnalyticsEvent(chatflowId, userId, roomId, eventTs, ingestedAt);
        return sqsMessage(sqsId, MAPPER.writeValueAsString(ae));
    }

    // ---- Redis fake stubs ----------------------------------------------------

    /**
     * RedisAnalytics stub that captures invocations and returns a configurable
     * first-seen count.
     */
    private static class CapturingAnalytics extends RedisAnalytics {
        final List<List<ProjectionEvent>> calls = new ArrayList<>();
        final long firstSeenPerCall;

        CapturingAnalytics(long firstSeenPerCall) {
            super(CdcConfig.fromEnv()); // REDIS_ENDPOINT blank -> isEnabled()=false
            this.firstSeenPerCall = firstSeenPerCall;
        }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public long recordBatchAnalytics(List<ProjectionEvent> events) {
            calls.add(new ArrayList<>(events));
            return firstSeenPerCall;
        }
    }

    /** RedisAnalytics stub that always throws on recordBatchAnalytics. */
    private static class ThrowingAnalytics extends RedisAnalytics {
        ThrowingAnalytics() { super(CdcConfig.fromEnv()); }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public long recordBatchAnalytics(List<ProjectionEvent> events) {
            throw new RuntimeException("Redis connection refused");
        }
    }

    // ---- Tests ---------------------------------------------------------------

    @Test
    void nullEvent_returnsEmptyFailures() {
        SQSBatchResponse resp = RedisAnalyticsHandler.processEvent(null, null);
        assertNotNull(resp);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    @Test
    void emptyRecordList_returnsEmptyFailures() {
        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(List.of()), null);
        assertTrue(resp.getBatchItemFailures().isEmpty());
    }

    /**
     * All valid messages: recordBatchAnalytics called exactly once with the full
     * batch; no failures returned.
     */
    @Test
    void validBatch_analyticsCalledOnceWithAllEvents() throws Exception {
        CapturingAnalytics analytics = new CapturingAnalytics(2L);
        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-1", "msg-a", "user-1", "room-1", 60_000L, 61_000L),
            analyticsMessage("sqs-2", "msg-b", "user-2", "room-1", 61_000L, 62_000L)
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), analytics);

        assertTrue(resp.getBatchItemFailures().isEmpty());
        assertEquals(1, analytics.calls.size(),
            "recordBatchAnalytics must be called exactly once per Lambda invocation");
        assertEquals(2, analytics.calls.get(0).size(),
            "both events must be passed to analytics");
    }

    /**
     * Verify bucket computation from AnalyticsEvent.toProjectionEvent() and
     * the resulting Lua ARGV array layout.
     */
    @Test
    void batchArgs_bucketsComputedCorrectly() throws Exception {
        // eventTsMs = 120_500 -> minuteBucket = 2, secondBucket = 120
        CapturingAnalytics analytics = new CapturingAnalytics(1L);
        SQSMessage msg =
            analyticsMessage("sqs-1", "msg-x", "user-x", "room-x", 120_500L, 121_000L);

        RedisAnalyticsHandler.processEvent(sqsEvent(List.of(msg)), analytics);

        assertEquals(1, analytics.calls.size());
        ProjectionEvent pe = analytics.calls.get(0).get(0);
        assertEquals("msg-x",  pe.messageId);
        assertEquals("user-x", pe.userId);
        assertEquals("room-x", pe.roomId);
        assertEquals(120_500L, pe.eventTsMs);
        assertEquals(121_000L, pe.ingestedAtMs);
        assertEquals(2L,   pe.minuteBucket(), "minute = 120500/60000");
        assertEquals(120L, pe.secondBucket(), "second = 120500/1000");

        String[] args =
            RedisAnalytics.buildBatchAnalyticsArgs(analytics.calls.get(0), 3600);
        assertEquals("3600",   args[0]); // ttl
        assertEquals("121000", args[1]); // healthTs
        assertEquals("msg-x",  args[2]); // healthId
        assertEquals("1",      args[3]); // count
        assertEquals("msg-x",  args[4]); // record[0] messageId
        assertEquals("user-x", args[5]); // record[0] userId
        assertEquals("room-x", args[6]); // record[0] roomId
        assertEquals("2",      args[7]); // record[0] minute
        assertEquals("120",    args[8]); // record[0] second
    }

    /**
     * Duplicate SQS messages (same chatflow messageId): both forwarded to
     * recordBatchAnalytics; the Lua SET NX EX handles deduplication internally.
     * The handler itself does not filter duplicates.
     */
    @Test
    void duplicateMessages_passedToAnalytics_firstSeenCounted() throws Exception {
        CapturingAnalytics analytics = new CapturingAnalytics(1L); // only 1 first-seen
        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-1", "msg-dup", "user-1", "room-1", 60_000L, 61_000L),
            analyticsMessage("sqs-2", "msg-dup", "user-1", "room-1", 60_000L, 61_000L)
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), analytics);

        assertTrue(resp.getBatchItemFailures().isEmpty(), "no SQS failures on success");
        assertEquals(1, analytics.calls.size());
        // Both events forwarded; Lua script decides dedupe inside Redis
        assertEquals(2, analytics.calls.get(0).size(),
            "duplicate messages must both be forwarded (Lua deduplicates internally)");
    }

    /**
     * Redis failure: all successfully-parsed SQS message IDs enter
     * batchItemFailures.  SQS redelivers the whole batch.
     */
    @Test
    void redisFailure_allParsedMessagesInBatchItemFailures() throws Exception {
        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-1", "msg-a", "u1", "r1", 1000L, 2000L),
            analyticsMessage("sqs-2", "msg-b", "u2", "r1", 1001L, 2001L)
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), new ThrowingAnalytics());

        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> sqsIds = resp.getBatchItemFailures().stream()
            .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(sqsIds.contains("sqs-1"));
        assertTrue(sqsIds.contains("sqs-2"));
    }

    /**
     * Parse failure for one message: that SQS message enters failures; the
     * remaining valid messages are still processed by Redis.
     */
    @Test
    void parseFailure_onlyBadMessageInFailures_restProcessed() throws Exception {
        CapturingAnalytics analytics = new CapturingAnalytics(1L);
        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-ok",  "msg-ok", "u1", "r1", 1000L, 2000L),
            sqsMessage("sqs-bad", "{ not valid json ~~~")
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), analytics);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("sqs-bad", resp.getBatchItemFailures().get(0).getItemIdentifier());
        assertEquals(1, analytics.calls.size());
        assertEquals(1, analytics.calls.get(0).size());
        assertEquals("msg-ok", analytics.calls.get(0).get(0).messageId);
    }

    // ---- Redis disabled tests (fix: must NOT silently drop) -----------------

    /**
     * Redis not enabled: all successfully-parsed SQS messages enter
     * batchItemFailures.  Messages must not be silently consumed.
     */
    @Test
    void redisDisabled_parsedMessagesMustFail() throws Exception {
        // Use a real RedisAnalytics with no REDIS_ENDPOINT -> isEnabled() = false
        RedisAnalytics disabledAnalytics = new RedisAnalytics(CdcConfig.fromEnv());
        assertFalse(disabledAnalytics.isEnabled(), "pre-condition: Redis must be disabled");

        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-1", "msg-a", "u1", "r1", 1000L, 2000L),
            analyticsMessage("sqs-2", "msg-b", "u2", "r1", 1001L, 2001L)
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), disabledAnalytics);

        assertEquals(2, resp.getBatchItemFailures().size(),
            "Redis disabled: all parsed messages must fail to prevent analytics loss");
        List<String> sqsIds = resp.getBatchItemFailures().stream()
            .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(sqsIds.contains("sqs-1"));
        assertTrue(sqsIds.contains("sqs-2"));
    }

    /**
     * Parse failure + Redis disabled: the parse-failed message fails for its own
     * reason; the successfully-parsed messages also fail because Redis is disabled.
     * The failure sets must be the union.
     */
    @Test
    void parseFailure_and_redisDisabled_bothFailureSetsUnioned() throws Exception {
        RedisAnalytics disabledAnalytics = new RedisAnalytics(CdcConfig.fromEnv());

        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-ok",  "msg-ok", "u1", "r1", 1000L, 2000L),
            sqsMessage("sqs-bad", "{ not json }")
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), disabledAnalytics);

        // sqs-bad: parse failure; sqs-ok: parsed fine but Redis disabled -> both fail
        assertEquals(2, resp.getBatchItemFailures().size());
        List<String> sqsIds = resp.getBatchItemFailures().stream()
            .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
            .toList();
        assertTrue(sqsIds.contains("sqs-bad"), "parse failure must be in failures");
        assertTrue(sqsIds.contains("sqs-ok"),  "redis-disabled parsed message must fail");
    }

    /**
     * Null analytics reference: same as disabled -- all parsed messages fail.
     */
    @Test
    void nullAnalytics_parsedMessagesMustFail() throws Exception {
        List<SQSMessage> msgs = List.of(
            analyticsMessage("sqs-1", "msg-a", "u1", "r1", 1000L, 2000L)
        );

        SQSBatchResponse resp =
            RedisAnalyticsHandler.processEvent(sqsEvent(msgs), null);

        assertEquals(1, resp.getBatchItemFailures().size());
        assertEquals("sqs-1", resp.getBatchItemFailures().get(0).getItemIdentifier());
    }
}
