package com.chatflow.consumerv3;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes unrecoverable messages to an SQS Dead-Letter Queue.
 *
 * <h3>When to use</h3>
 * <ul>
 *   <li><b>PARSE_ERROR</b> — malformed Protobuf payload, blank required fields.
 *       Called immediately, no retries.</li>
 *   <li><b>TERMINAL</b> — DynamoDB write retries exhausted, or hard AWS error.
 *       Called after {@link PersistenceWriter} returns a terminal result.</li>
 * </ul>
 *
 * <h3>Message format</h3>
 * <b>Body (JSON):</b>
 * <pre>
 * {
 *   "messageId"        : "...",
 *   "roomId"           : "...",
 *   "userId"           : "...",
 *   "failureType"      : "TERMINAL",
 *   "errorMessage"     : "...",
 *   "retryCount"       : 5,
 *   "failedAt"         : 1700000000000,
 *   "queueName"        : "room.5",
 *   "deliveryTag"      : 42,
 *   "rawPayloadBase64" : "<base64 of original Protobuf bytes>"
 * }
 * </pre>
 * <b>Message attributes</b> carry a subset for quick filtering without
 * deserializing the body.
 *
 * <h3>Atomicity guarantee</h3>
 * Callers <em>must not</em> ack the RabbitMQ delivery unless this method
 * returns {@link DlqPublishResult#isSuccess()}.  If SQS publish fails,
 * the caller must {@code basicNack(requeue=true)} to avoid message loss.
 *
 * <p>Thread-safe: stateless except for the shared {@link SqsClient}.
 */
public class DlqPublisher {

    private static final Logger log = LogManager.getLogger(DlqPublisher.class);

    private final SqsClient    sqsClient;
    private final String       dlqUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DlqPublisher(SqsClient sqsClient, String dlqUrl) {
        this.sqsClient = sqsClient;
        this.dlqUrl    = dlqUrl;
    }

    /** {@code true} when a DLQ URL is configured and SQS client is available. */
    public boolean isEnabled() {
        return sqsClient != null && dlqUrl != null && !dlqUrl.isBlank();
    }

    /**
     * Publish a failed message to the SQS DLQ.
     *
     * @param envelope     the original RabbitMQ delivery (provides raw payload + metadata)
     * @param record       the parsed record; {@code null} for PARSE_ERROR failures
     * @param failureType  reason category
     * @param errorMessage human-readable error detail
     * @param retryCount   number of retry attempts made before giving up
     * @return {@link DlqPublishResult} — caller must inspect before acking
     */
    public DlqPublishResult publish(
            QueueEnvelope envelope,
            CanonicalMessageRecord record,
            FailureType failureType,
            String errorMessage,
            int retryCount) {

        if (!isEnabled()) {
            String id = record != null ? record.getMessageId() : "<unknown>";
            log.warn("DLQ not configured — dropping messageId={} failureType={}", id, failureType);
            // DISABLED: caller treats this as "best-effort acknowledged"
            return DlqPublishResult.disabled();
        }

        String messageId = record != null ? record.getMessageId() : "";
        String roomId    = record != null ? record.getRoomId()    : "";
        String userId    = record != null ? record.getUserId()    : "";

        try {
            // ── Body (JSON) ───────────────────────────────────────────────────
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messageId",        messageId);
            body.put("roomId",           roomId);
            body.put("userId",           userId);
            body.put("failureType",      failureType.name());
            body.put("errorMessage",     errorMessage != null ? errorMessage : "");
            body.put("retryCount",       retryCount);
            body.put("failedAt",         System.currentTimeMillis());
            body.put("queueName",        envelope.getQueueName());
            body.put("deliveryTag",      envelope.getDeliveryTag());
            body.put("rawPayloadBase64", Base64.getEncoder()
                                               .encodeToString(envelope.getPayload()));

            String bodyJson = objectMapper.writeValueAsString(body);

            // ── Message attributes (quick filtering, max 10) ──────────────────
            Map<String, MessageAttributeValue> attrs = new HashMap<>();
            attrs.put("failureType", strAttr(failureType.name()));
            attrs.put("retryCount",  numAttr(retryCount));
            if (!messageId.isBlank()) attrs.put("messageId", strAttr(messageId));
            if (!roomId.isBlank())    attrs.put("roomId",    strAttr(roomId));
            if (!userId.isBlank())    attrs.put("userId",    strAttr(userId));

            // ── Send ──────────────────────────────────────────────────────────
            SendMessageRequest req = SendMessageRequest.builder()
                .queueUrl(dlqUrl)
                .messageBody(bodyJson)
                .messageAttributes(attrs)
                .build();

            sqsClient.sendMessage(req);
            log.info("DLQ published: messageId='{}' failureType={} retries={}",
                messageId, failureType, retryCount);
            return DlqPublishResult.success();

        } catch (Exception e) {
            log.error("DLQ publish FAILED for messageId='{}' failureType={}: {}",
                messageId, failureType, e.getMessage());
            return DlqPublishResult.failure(e.getMessage());
        }
    }

    // ── SQS attribute helpers ─────────────────────────────────────────────────

    private static MessageAttributeValue strAttr(String value) {
        return MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value != null ? value : "")
            .build();
    }

    private static MessageAttributeValue numAttr(int value) {
        return MessageAttributeValue.builder()
            .dataType("Number")
            .stringValue(Integer.toString(value))
            .build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void close() {
        if (sqsClient != null) {
            try { sqsClient.close(); } catch (Exception ignored) {}
        }
    }
}
