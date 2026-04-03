package com.chatflow.consumerv3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Writes batches of {@link CanonicalMessageRecord}s to the canonical DynamoDB
 * table ({@code messages_by_id}) using AWS SDK v2.
 *
 * <h3>Idempotency</h3>
 * Each record is written with:
 * <pre>ConditionExpression: attribute_not_exists(messageId)</pre>
 * If the condition fails the item already exists →
 * {@link PersistenceRecordResult.Status#DUPLICATE} (safe to ack).
 *
 * <h3>Retry policy</h3>
 * Transient failures ({@link FailureType#TRANSIENT}) are retried with
 * exponential back-off + jitter via {@link RetryPolicy}.  Virtual threads
 * park during {@code Thread.sleep}, so platform threads are never blocked.
 * After retries are exhausted the result is escalated to
 * {@link FailureType#TERMINAL} so the caller can route to the DLQ.
 *
 * <h3>Semaphore</h3>
 * A fair {@link Semaphore} limits the number of concurrently in-flight
 * {@link #writeBatch} calls, bounding DynamoDB concurrency.
 */
public class PersistenceWriter {

    private static final Logger log = LogManager.getLogger(PersistenceWriter.class);

    private static final String IDEMPOTENCY_CONDITION = "attribute_not_exists(messageId)";

    private final DynamoDbClient  dynamoClient;
    private final String          canonicalTableName;
    private final Semaphore       semaphore;
    private final RetryPolicy     retryPolicy;
    private final ConsumerMetrics metrics;

    public PersistenceWriter(DynamoDbClient dynamoClient,
                             String canonicalTableName,
                             int semaphorePermits,
                             RetryPolicy retryPolicy,
                             ConsumerMetrics metrics) {
        this.dynamoClient      = dynamoClient;
        this.canonicalTableName = canonicalTableName;
        this.semaphore          = new Semaphore(semaphorePermits, true);
        this.retryPolicy        = retryPolicy;
        this.metrics            = metrics;
    }

    /**
     * Write all records to DynamoDB and return a {@link PersistenceBatchResult}.
     *
     * <p>Each record is written individually so that conditional expressions
     * can be applied per-item (BatchWriteItem does not support conditions).
     * Transient failures are retried internally; after retries are exhausted
     * the record is returned as {@link FailureType#TERMINAL}.
     *
     * <p>Designed to run on a virtual thread.
     */
    public PersistenceBatchResult writeBatch(List<CanonicalMessageRecord> records) {
        if (records.isEmpty()) {
            return new PersistenceBatchResult(List.of());
        }

        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<PersistenceRecordResult> failed = new ArrayList<>(records.size());
            for (CanonicalMessageRecord r : records) {
                failed.add(PersistenceRecordResult.terminalFailure(
                    r.getMessageId(), "semaphore interrupted", 0));
            }
            return new PersistenceBatchResult(failed);
        }

        try {
            List<PersistenceRecordResult> results = new ArrayList<>(records.size());
            for (CanonicalMessageRecord record : records) {
                results.add(writeWithRetry(record));
            }
            return new PersistenceBatchResult(results);
        } finally {
            semaphore.release();
        }
    }

    // ── Single-record write with retry loop ───────────────────────────────────

    private PersistenceRecordResult writeWithRetry(CanonicalMessageRecord r) {
        int attempt = 1;
        while (true) {
            PersistenceRecordResult result = writeOnce(r);

            // SUCCESS or DUPLICATE → done
            if (!result.isFailure()) {
                return result;
            }

            // TERMINAL failure (hard error) → skip retry
            if (result.isTerminalFailure()) {
                metrics.incTerminalFailures();
                return result;
            }

            // TRANSIENT failure
            metrics.incTransientFailures();

            if (!retryPolicy.shouldRetry(attempt)) {
                // Retry budget exhausted — escalate to TERMINAL
                metrics.incTerminalFailures();
                log.warn("Transient failure for messageId={} exhausted {} retries, escalating to TERMINAL: {}",
                    r.getMessageId(), attempt - 1, result.getErrorMessage());
                return PersistenceRecordResult.terminalFailure(
                    r.getMessageId(),
                    "retries_exhausted(" + (attempt - 1) + "): " + result.getErrorMessage(),
                    attempt - 1);
            }

            long delayMs = retryPolicy.delayMs(attempt);
            log.warn("Transient failure for messageId={}, attempt={}/{}, retrying in {}ms: {}",
                r.getMessageId(), attempt, retryPolicy.getMaxRetries(),
                delayMs, result.getErrorMessage());
            metrics.incRetries();

            try {
                retryPolicy.sleep(attempt);   // parks virtual thread — no carrier thread blocked
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                metrics.incTerminalFailures();
                return PersistenceRecordResult.terminalFailure(
                    r.getMessageId(), "retry_interrupted at attempt " + attempt, attempt - 1);
            }
            attempt++;
        }
    }

    // ── Single attempt (no retry) ─────────────────────────────────────────────

    private PersistenceRecordResult writeOnce(CanonicalMessageRecord r) {
        try {
            PutItemRequest request = PutItemRequest.builder()
                .tableName(canonicalTableName)
                .item(buildItem(r))
                .conditionExpression(IDEMPOTENCY_CONDITION)
                .build();

            dynamoClient.putItem(request);
            log.debug("Persisted messageId={} roomId={} seq={}",
                r.getMessageId(), r.getRoomId(), r.getRoomSequence());
            return PersistenceRecordResult.success(r.getMessageId());

        } catch (ConditionalCheckFailedException e) {
            // Idempotent duplicate — treat as success for ack purposes
            log.debug("Duplicate messageId={} (already in canonical table)", r.getMessageId());
            return PersistenceRecordResult.duplicate(r.getMessageId());

        } catch (Exception e) {
            FailureType type = FailureClassifier.classify(e);
            log.warn("[{}] DynamoDB write failed for messageId={}: {}",
                type, r.getMessageId(), e.getMessage());
            if (type == FailureType.TRANSIENT) {
                return PersistenceRecordResult.transientFailure(r.getMessageId(), e.getMessage());
            } else {
                return PersistenceRecordResult.terminalFailure(r.getMessageId(), e.getMessage(), 0);
            }
        }
    }

    // ── DynamoDB item builder ─────────────────────────────────────────────────

    /**
     * Build the DynamoDB attribute map for {@link PutItemRequest}.
     * Package-private for use in unit tests.
     */
    static Map<String, AttributeValue> buildItem(CanonicalMessageRecord r) {
        Map<String, AttributeValue> item = new HashMap<>(16);
        item.put("messageId",    s(r.getMessageId()));
        item.put("roomId",       s(r.getRoomId()));
        item.put("userId",       s(r.getUserId()));
        item.put("username",     s(r.getUsername()));
        item.put("message",      s(r.getMessage()));
        item.put("messageType",  s(r.getMessageType()));
        item.put("roomSequence", n(r.getRoomSequence()));
        item.put("eventTs",      n(r.getEventTs()));
        item.put("ingestedAt",   n(r.getIngestedAt()));
        putIfNotBlank(item, "serverId",  r.getServerId());
        putIfNotBlank(item, "clientIp",  r.getClientIp());
        putIfNotBlank(item, "dayBucket", r.getDayBucket());
        return item;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value != null ? value : "");
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    private static void putIfNotBlank(Map<String, AttributeValue> item,
                                      String key, String value) {
        if (value != null && !value.isBlank()) {
            item.put(key, s(value));
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void close() {
        if (dynamoClient != null) {
            try { dynamoClient.close(); } catch (Exception ignored) {}
        }
    }
}
