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
 * AWS Lambda handler for the DynamoDB Streams CDC projector.
 *
 * <h3>Failure strategy - partial batch failure</h3>
 * The handler returns {@link StreamsEventResponse} with a
 * {@code batchItemFailures} list.  Records that fail processing are returned
 * by their {@code sequenceNumber}; Lambda retries only those records (shard
 * position advances past successful records).  This requires:
 * <ul>
 *   <li>Event source mapping configured with
 *       {@code function_response_types = ["ReportBatchItemFailures"]}
 *       (see {@code assignment3.tf}).</li>
 *   <li>Response type {@code StreamsEventResponse} (not {@code Void}).</li>
 * </ul>
 * MODIFY and REMOVE events are silently skipped - they are intentional no-ops,
 * not failures, and are never added to {@code batchItemFailures}.
 *
 * <h3>Pipeline per INSERT record</h3>
 * <ol>
 *   <li>Parse {@link ProjectionEvent} from {@code NEW_IMAGE}.</li>
 *   <li>Write DynamoDB projections ({@link ProjectionWriter}).</li>
 *   <li>Atomically dedupe + record Redis analytics via Lua script
 *       ({@link RedisAnalytics#dedupeAndRecordAnalytics}).</li>
 *   <li>Update Redis projection health markers (only on first-seen).</li>
 * </ol>
 *
 * <h3>Lambda warm-path reuse</h3>
 * {@link ProjectionWriter} and {@link RedisAnalytics} are initialised once in
 * the {@code static} block and reused across invocations in the same execution
 * context.
 */
public class CdcLambdaHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

    private static final Logger log = LogManager.getLogger(CdcLambdaHandler.class);

    private static final CdcConfig       config;
    private static final ProjectionWriter projectionWriter;
    private static final RedisAnalytics   redisAnalytics;

    private record PendingInsert(
            String seqNum,
            String eventId,
            ProjectionEvent projectionEvent) {}

    static {
        config           = CdcConfig.fromEnv();
        projectionWriter = new ProjectionWriter(config);
        redisAnalytics   = new RedisAnalytics(config);
        log.info("CdcLambdaHandler initialised: {}", config);
    }

    @Override
    public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
        return processEvent(event, projectionWriter, redisAnalytics);
    }

    /**
     * Package-private so unit tests can inject fakes for writer/analytics
     * without triggering the static initialiser.
     */
    static StreamsEventResponse processEvent(
            DynamodbEvent event,
            ProjectionWriter writer,
            RedisAnalytics analytics) {

        List<BatchItemFailure> failures = new ArrayList<>();

        if (event == null || event.getRecords() == null) {
            return emptyResponse();
        }

        int processed = 0, skipped = 0;
        List<PendingInsert> inserts = new ArrayList<>();

        for (DynamodbStreamRecord record : event.getRecords()) {

            // MODIFY and REMOVE are intentional no-ops - not failures
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
                // Unexpected: INSERT with missing NewImage - treat as failure so it's retried
                log.error("INSERT record eventID={} seqNum={} has null NewImage - marking as failure",
                    record.getEventID(), seqNum);
                addFailure(failures, seqNum);
                continue;
            }

            try {
                ProjectionEvent pe = ProjectionEvent.fromNewImage(newImage);
                log.debug("Projecting messageId={} roomId={}", pe.messageId, pe.roomId);
                inserts.add(new PendingInsert(seqNum, record.getEventID(), pe));

            } catch (Exception e) {
                // Record the failure so Lambda retries this record
                log.error("Failed to project record seqNum={} eventID={}: {}",
                    seqNum, record.getEventID(), e.getMessage(), e);
                addFailure(failures, seqNum);
            }
        }

        if (writer == null && !inserts.isEmpty()) {
            log.error("ProjectionWriter is null with {} INSERT records pending", inserts.size());
            for (PendingInsert insert : inserts) {
                addFailure(failures, insert.seqNum());
            }
            return responseFor(failures, processed, skipped);
        }

        Set<String> projectionWriteFailures = new HashSet<>();
        if (!inserts.isEmpty()) {
            try {
                ProjectionWriter.BatchWriteResult batchResult = writer.writeBatch(
                    inserts.stream()
                        .map(PendingInsert::projectionEvent)
                        .toList()
                );
                projectionWriteFailures.addAll(batchResult.failedMessageIds());
            } catch (Exception e) {
                log.error("Projection batch write failed for {} records: {}",
                    inserts.size(), e.getMessage(), e);
                for (PendingInsert insert : inserts) {
                    addFailure(failures, insert.seqNum());
                }
                return responseFor(failures, processed, skipped);
            }
        }

        for (PendingInsert insert : inserts) {
            ProjectionEvent pe = insert.projectionEvent();
            if (projectionWriteFailures.contains(pe.messageId)) {
                log.error("Projection writes incomplete for messageId={} seqNum={} eventID={}",
                    pe.messageId, insert.seqNum(), insert.eventId());
                addFailure(failures, insert.seqNum());
            }
        }

        List<PendingInsert> analyticsPending = inserts.stream()
            .filter(insert -> !projectionWriteFailures.contains(insert.projectionEvent().messageId))
            .toList();

        if (!analyticsPending.isEmpty() && analytics != null && analytics.isEnabled()) {
            try {
                long firstSeenCount = analytics.recordBatchAnalytics(
                    analyticsPending.stream()
                        .map(PendingInsert::projectionEvent)
                        .toList()
                );
                log.debug("Redis batch analytics done records={} firstSeen={}",
                    analyticsPending.size(), firstSeenCount);
            } catch (Exception e) {
                log.error("Failed to record batch analytics for {} records: {}",
                    analyticsPending.size(), e.getMessage(), e);
                for (PendingInsert insert : analyticsPending) {
                    addFailure(failures, insert.seqNum());
                }
                return responseFor(failures, processed, skipped);
            }
        }

        processed += analyticsPending.size();

        return responseFor(failures, processed, skipped);
    }

    private static StreamsEventResponse responseFor(
            List<BatchItemFailure> failures,
            int processed,
            int skipped) {
        log.info("CDC batch done: processed={} skipped={} failures={}",
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
