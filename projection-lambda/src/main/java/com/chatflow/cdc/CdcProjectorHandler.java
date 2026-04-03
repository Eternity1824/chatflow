package com.chatflow.cdc;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse.BatchItemFailure;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lambda A: DynamoDB Stream -> DynamoDB projections -> SQS analytics queue.
 *
 * <h3>Pipeline per INSERT record</h3>
 * <ol>
 *   <li>Parse {@link ProjectionEvent} from {@code NEW_IMAGE}.</li>
 *   <li>Batch-write projections to room_messages, user_messages, user_rooms via
 *       {@link ProjectionWriter} (same batch-write strategy as before).</li>
 *   <li>For every record that was successfully projected, publish an
 *       {@link AnalyticsEvent} to SQS so {@link RedisAnalyticsHandler} can
 *       asynchronously update Redis.</li>
 * </ol>
 *
 * <h3>Failure semantics</h3>
 * <ul>
 *   <li>DynamoDB projection failure: stream batch item failure (record retried).</li>
 *   <li>SQS send failure: stream batch item failure for those records only.
 *       Projection already succeeded; re-running is safe because writes are
 *       idempotent via attribute_not_exists conditions.</li>
 *   <li>SQS publisher null (SQS_ANALYTICS_QUEUE_URL not configured): all
 *       successfully projected records are marked as failures so the stream
 *       retries them.  This is intentional -- a missing publisher means analytics
 *       will never reach Redis, which is a misconfiguration, not a skip.</li>
 *   <li>Failures are isolated: a failing record never blocks a succeeding one.</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * Projection writes use {@code attribute_not_exists(sk)} conditions; retries
 * produce duplicates that are silently ignored.  Redis dedupe is handled inside
 * Lambda B's Lua script.
 */
public class CdcProjectorHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

    private static final Logger log = LogManager.getLogger(CdcProjectorHandler.class);

    private static final CdcConfig       config;
    private static final ProjectionWriter projectionWriter;
    private static final SqsPublisher    sqsPublisher;

    private record PendingInsert(
            String seqNum,
            String eventId,
            ProjectionEvent projectionEvent) {}

    static {
        config           = CdcConfig.fromEnv();
        projectionWriter = new ProjectionWriter(config);
        sqsPublisher     = config.isSqsAnalyticsEnabled() ? new SqsPublisher(config) : null;
        log.info("CdcProjectorHandler initialised: {}", config);
    }

    @Override
    public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
        return processEvent(event, projectionWriter, sqsPublisher);
    }

    /**
     * Package-private so unit tests can inject fakes without triggering the
     * static initialiser.
     *
     * <p>Passing {@code null} for {@code publisher} simulates a missing
     * SQS_ANALYTICS_QUEUE_URL and causes all successfully projected records
     * to be returned as {@code batchItemFailures} (not silently consumed).
     */
    static StreamsEventResponse processEvent(
            DynamodbEvent event,
            ProjectionWriter writer,
            SqsPublisher publisher) {

        List<BatchItemFailure> failures = new ArrayList<>();

        if (event == null || event.getRecords() == null) {
            return emptyResponse();
        }

        int processed = 0, skipped = 0;
        List<PendingInsert> inserts = new ArrayList<>();

        // Phase 0: parse stream records
        for (DynamodbStreamRecord record : event.getRecords()) {
            if (!"INSERT".equals(record.getEventName())) {
                skipped++;
                continue;
            }

            String seqNum = record.getDynamodb() != null
                ? record.getDynamodb().getSequenceNumber()
                : null;

            Map<String, AttributeValue> newImage = record.getDynamodb() != null
                ? record.getDynamodb().getNewImage()
                : null;

            if (newImage == null) {
                log.error("INSERT record eventID={} seqNum={} has null NewImage",
                    record.getEventID(), seqNum);
                addFailure(failures, seqNum);
                continue;
            }

            try {
                ProjectionEvent pe = ProjectionEvent.fromNewImage(newImage);
                inserts.add(new PendingInsert(seqNum, record.getEventID(), pe));
            } catch (Exception e) {
                log.error("Failed to parse record seqNum={} eventID={}: {}",
                    seqNum, record.getEventID(), e.getMessage(), e);
                addFailure(failures, seqNum);
            }
        }

        if (inserts.isEmpty()) {
            return responseFor(failures, processed, skipped);
        }

        // Phase 1: write DynamoDB projections
        Set<String> projectionFailed = new HashSet<>();
        try {
            ProjectionWriter.BatchWriteResult result = writer.writeBatch(
                inserts.stream().map(PendingInsert::projectionEvent).toList());
            projectionFailed.addAll(result.failedMessageIds());
        } catch (Exception e) {
            log.error("Projection batch write threw exception for {} records: {}",
                inserts.size(), e.getMessage(), e);
            for (PendingInsert insert : inserts) {
                addFailure(failures, insert.seqNum());
            }
            return responseFor(failures, processed, skipped);
        }

        for (PendingInsert insert : inserts) {
            if (projectionFailed.contains(insert.projectionEvent().messageId)) {
                log.error("Projection failed messageId={} seqNum={}",
                    insert.projectionEvent().messageId, insert.seqNum());
                addFailure(failures, insert.seqNum());
            }
        }

        // Phase 2: publish successfully projected records to SQS analytics queue
        List<PendingInsert> sqsPending = inserts.stream()
            .filter(i -> !projectionFailed.contains(i.projectionEvent().messageId))
            .toList();

        if (sqsPending.isEmpty()) {
            return responseFor(failures, processed, skipped);
        }

        if (publisher == null) {
            // SQS_ANALYTICS_QUEUE_URL is not configured.
            // Projections already landed in DynamoDB but analytics events cannot
            // be forwarded to Redis via SQS.  Failing these records forces the
            // stream to retry, surfacing the misconfiguration rather than silently
            // dropping the analytics pipeline.
            log.error("SQS publisher not configured (SQS_ANALYTICS_QUEUE_URL blank)."
                + " Failing {} projected records to prevent silent analytics loss.",
                sqsPending.size());
            for (PendingInsert insert : sqsPending) {
                addFailure(failures, insert.seqNum());
            }
            return responseFor(failures, processed, skipped);
        }

        List<AnalyticsEvent> analyticsEvents = sqsPending.stream()
            .map(i -> AnalyticsEvent.from(i.projectionEvent()))
            .toList();

        Set<String> sqsFailed = publisher.sendBatch(analyticsEvents);

        for (PendingInsert insert : sqsPending) {
            if (sqsFailed.contains(insert.projectionEvent().messageId)) {
                log.error("SQS send failed messageId={} seqNum={}",
                    insert.projectionEvent().messageId, insert.seqNum());
                addFailure(failures, insert.seqNum());
            } else {
                processed++;
            }
        }

        return responseFor(failures, processed, skipped);
    }

    private static StreamsEventResponse responseFor(
            List<BatchItemFailure> failures, int processed, int skipped) {
        log.info("CdcProjector batch done: processed={} skipped={} failures={}",
            processed, skipped, failures.size());
        return StreamsEventResponse.builder()
            .withBatchItemFailures(failures)
            .build();
    }

    private static void addFailure(List<BatchItemFailure> failures, String seqNum) {
        if (seqNum != null) {
            failures.add(BatchItemFailure.builder()
                .withItemIdentifier(seqNum)
                .build());
        }
    }

    private static StreamsEventResponse emptyResponse() {
        return StreamsEventResponse.builder()
            .withBatchItemFailures(List.of())
            .build();
    }
}
