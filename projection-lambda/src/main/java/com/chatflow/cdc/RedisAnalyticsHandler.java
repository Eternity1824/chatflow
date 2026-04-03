package com.chatflow.cdc;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Lambda B: SQS analytics queue -> Redis analytics.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Parse {@link AnalyticsEvent} from each SQS message body (JSON).</li>
 *   <li>Convert to lightweight {@link ProjectionEvent} stubs that carry only
 *       the fields consumed by {@link RedisAnalytics}.</li>
 *   <li>Call {@link RedisAnalytics#recordBatchAnalytics} once for the whole
 *       batch -- a single Lua {@code EVAL} atomically deduplicates, aggregates
 *       counters, and advances projection health markers.</li>
 * </ol>
 *
 * <h3>Failure semantics</h3>
 * <ul>
 *   <li>JSON parse failure for a single message: that SQS message ID is added
 *       to {@code batchItemFailures}; remaining messages are still processed.</li>
 *   <li>Redis not enabled (REDIS_ENDPOINT blank): all successfully-parsed SQS
 *       message IDs are added to {@code batchItemFailures}.  This forces SQS to
 *       redeliver the messages instead of silently dropping analytics data.
 *       Parse failures are still reported individually.</li>
 *   <li>Redis call failure: all successfully-parsed SQS message IDs are added
 *       to {@code batchItemFailures} so the entire batch is retried by SQS.</li>
 * </ul>
 *
 * <h3>Deduplication</h3>
 * The Lua script inside {@link RedisAnalytics} sets
 * {@code analytics:processed:{messageId}} with {@code NX EX ttl}.  Retried
 * SQS messages for the same chatflow messageId are silently ignored by Redis.
 *
 * <h3>Health markers</h3>
 * {@code projection:lastProjectedIngestedAt} and
 * {@code projection:lastProjectedMessageId} are advanced at most once per
 * batch (to the record with the highest {@code ingestedAtMs}) and only move
 * forward.
 */
public class RedisAnalyticsHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger log = LogManager.getLogger(RedisAnalyticsHandler.class);

    private static final RedisAnalytics redisAnalytics;
    private static final ObjectMapper   mapper = new ObjectMapper();

    static {
        CdcConfig config = CdcConfig.fromEnv();
        redisAnalytics   = new RedisAnalytics(config);
        log.info("RedisAnalyticsHandler initialised redis={}", config.isRedisEnabled());
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        return processEvent(event, redisAnalytics);
    }

    /**
     * Package-private so unit tests can inject a fake {@link RedisAnalytics}
     * without triggering the static initialiser.
     *
     * <p>Passing {@code null} or a disabled {@link RedisAnalytics} simulates a
     * missing REDIS_ENDPOINT and causes all successfully-parsed SQS messages to
     * be returned as {@code batchItemFailures} (not silently consumed).
     */
    static SQSBatchResponse processEvent(SQSEvent event, RedisAnalytics analytics) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            return SQSBatchResponse.builder().withBatchItemFailures(List.of()).build();
        }

        List<SQSMessage> messages = event.getRecords();
        List<ProjectionEvent> analyticsEvents = new ArrayList<>(messages.size());
        // SQS message IDs of records that parsed successfully -- needed if Redis fails
        List<String> parsedSqsIds = new ArrayList<>(messages.size());

        // Phase 1: parse all messages; isolate parse failures per message
        for (SQSMessage msg : messages) {
            try {
                AnalyticsEvent ae = mapper.readValue(msg.getBody(), AnalyticsEvent.class);
                analyticsEvents.add(ae.toProjectionEvent());
                parsedSqsIds.add(msg.getMessageId());
            } catch (Exception e) {
                log.error("Failed to parse SQS message sqsId={}: {}",
                    msg.getMessageId(), e.getMessage(), e);
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                    .withItemIdentifier(msg.getMessageId())
                    .build());
            }
        }

        if (analyticsEvents.isEmpty()) {
            return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
        }

        if (analytics == null || !analytics.isEnabled()) {
            // REDIS_ENDPOINT is not configured but messages are waiting to be
            // processed.  Failing all parsed messages forces SQS to redeliver
            // them, preventing permanent analytics loss.  This is a
            // misconfiguration that must be fixed, not silently skipped.
            log.error("Redis not enabled (REDIS_ENDPOINT not set)."
                + " Failing {} parsed SQS messages to prevent silent analytics loss.",
                parsedSqsIds.size());
            for (String sqsId : parsedSqsIds) {
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                    .withItemIdentifier(sqsId)
                    .build());
            }
            return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
        }

        // Phase 2: batch analytics via single Lua call
        try {
            long firstSeen = analytics.recordBatchAnalytics(analyticsEvents);
            log.info("Redis analytics batch done: total={} firstSeen={}",
                analyticsEvents.size(), firstSeen);
        } catch (Exception e) {
            log.error("Redis recordBatchAnalytics failed -- failing all {} parsed messages: {}",
                parsedSqsIds.size(), e.getMessage(), e);
            for (String sqsId : parsedSqsIds) {
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                    .withItemIdentifier(sqsId)
                    .build());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }
}
