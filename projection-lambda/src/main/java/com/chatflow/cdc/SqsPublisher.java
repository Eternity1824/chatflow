package com.chatflow.cdc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes {@link AnalyticsEvent} messages to SQS in chunks of up to 10
 * (the {@code SendMessageBatch} limit).
 *
 * <h3>Failure semantics</h3>
 * <ul>
 *   <li>JSON serialization failure for one event: that chatflow messageId is
 *       added to the returned failed set immediately (the event is never sent).</li>
 *   <li>SQS batch response reports a partial failure: all SQS-reported failures
 *       are mapped back to their chatflow messageId and returned.</li>
 *   <li>SQS call throws an exception: all messageIds in the affected chunk are
 *       returned as failed.</li>
 * </ul>
 *
 * The caller ({@link CdcProjectorHandler}) maps failed messageIds back to
 * their DynamoDB stream sequence numbers and returns them as
 * {@code batchItemFailures} so Lambda retries those records.
 */
public class SqsPublisher {

    private static final Logger log = LogManager.getLogger(SqsPublisher.class);
    /** SQS SendMessageBatch accepts at most 10 entries per call. */
    private static final int SQS_BATCH_LIMIT = 10;

    private final SqsClient    sqsClient;
    private final String       queueUrl;
    private final ObjectMapper mapper;

    public SqsPublisher(CdcConfig config) {
        this.sqsClient = SqsClient.builder()
            .region(Region.of(config.dynamoRegion))
            .build();
        this.queueUrl = config.sqsAnalyticsQueueUrl;
        this.mapper   = new ObjectMapper();
    }

    /** Package-private constructor for unit tests that inject a fake client. */
    SqsPublisher(SqsClient sqsClient, String queueUrl) {
        this.sqsClient = sqsClient;
        this.queueUrl  = queueUrl;
        this.mapper    = new ObjectMapper();
    }

    /**
     * Send all events to SQS, chunked at {@value #SQS_BATCH_LIMIT} per call.
     *
     * @return chatflow messageIds for which the send definitively failed
     */
    public Set<String> sendBatch(List<AnalyticsEvent> events) {
        Set<String> failed = new HashSet<>();
        for (int start = 0; start < events.size(); start += SQS_BATCH_LIMIT) {
            int end = Math.min(start + SQS_BATCH_LIMIT, events.size());
            failed.addAll(sendChunk(events.subList(start, end)));
        }
        return failed;
    }

    private Set<String> sendChunk(List<AnalyticsEvent> chunk) {
        Set<String> failed = new HashSet<>();
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
        // batchEntryId (string index) -> chatflow messageId
        Map<String, String> idMap = new LinkedHashMap<>(chunk.size() * 2);

        for (int i = 0; i < chunk.size(); i++) {
            AnalyticsEvent event = chunk.get(i);
            String batchId = String.valueOf(i);
            try {
                String body = mapper.writeValueAsString(event);
                entries.add(SendMessageBatchRequestEntry.builder()
                    .id(batchId)
                    .messageBody(body)
                    .build());
                idMap.put(batchId, event.messageId);
            } catch (JsonProcessingException e) {
                log.error("Serialize failed messageId={}: {}", event.messageId, e.getMessage());
                failed.add(event.messageId);
            }
        }

        if (entries.isEmpty()) {
            return failed;
        }

        try {
            SendMessageBatchResponse response = sqsClient.sendMessageBatch(
                SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());
            for (BatchResultErrorEntry err : response.failed()) {
                String chatflowId = idMap.get(err.id());
                if (chatflowId != null) {
                    log.error("SQS batch send failed messageId={} batchId={} code={} msg={}",
                        chatflowId, err.id(), err.code(), err.message());
                    failed.add(chatflowId);
                }
            }
            log.debug("SQS chunk sent: total={} failed={}", entries.size(), response.failed().size());
        } catch (Exception e) {
            log.error("SQS sendMessageBatch threw exception, failing entire chunk: {}", e.getMessage(), e);
            failed.addAll(idMap.values());
        }

        return failed;
    }
}
