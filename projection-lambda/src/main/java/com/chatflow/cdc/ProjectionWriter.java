package com.chatflow.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Writes CDC projection records to three DynamoDB tables.
 *
 * <h3>Tables and key design</h3>
 * <ul>
 *   <li>{@code room_messages}:  PK={@code pk} = "roomId#yyyyMMdd",
 *       SK={@code sk} = "eventTsMs#messageId" - all messages in a room per day</li>
 *   <li>{@code user_messages}:  PK={@code pk} = "userId#yyyyMMdd",
 *       SK={@code sk} = "eventTsMs#messageId" - all messages by a user per day</li>
 *   <li>{@code user_rooms}: PK={@code userId}, SK={@code roomId},
 *       UpdateItem with conditional {@code lastActivityTs}</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * {@code room_messages} and {@code user_messages} use
 * {@code attribute_not_exists(sk)} so duplicate Lambda invocations (at-least-once)
 * are safe. {@code user_rooms} uses a conditional update that only advances
 * {@code lastActivityTs} when the new value is greater.
 */
public class ProjectionWriter {

    private static final Logger log = LogManager.getLogger(ProjectionWriter.class);
    private static final int MAX_BATCH_WRITE_SIZE = 25;
    private static final int MAX_BATCH_WRITE_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 25L;

    private final DynamoDbClient client;
    private final String         tableRoomMessages;
    private final String         tableUserMessages;
    private final String         tableUserRooms;

    public static final class BatchWriteResult {
        private static final BatchWriteResult SUCCESS = new BatchWriteResult(Set.of());

        private final Set<String> failedMessageIds;

        private BatchWriteResult(Set<String> failedMessageIds) {
            this.failedMessageIds = Set.copyOf(failedMessageIds);
        }

        public static BatchWriteResult success() {
            return SUCCESS;
        }

        public static BatchWriteResult failed(Set<String> failedMessageIds) {
            return failedMessageIds.isEmpty() ? SUCCESS : new BatchWriteResult(failedMessageIds);
        }

        public Set<String> failedMessageIds() {
            return failedMessageIds;
        }
    }

    public ProjectionWriter(CdcConfig config) {
        this.client = DynamoDbClient.builder()
            .region(Region.of(config.dynamoRegion))
            .build();
        this.tableRoomMessages = config.tableRoomMessages;
        this.tableUserMessages = config.tableUserMessages;
        this.tableUserRooms    = config.tableUserRooms;
    }

    /** For test subclasses that override {@link #write} - avoids building a real DynamoDB client. */
    protected ProjectionWriter() {
        this.client            = null;
        this.tableRoomMessages = null;
        this.tableUserMessages = null;
        this.tableUserRooms    = null;
    }

    /**
     * Write all three projections for one message.
     * Any {@link ConditionalCheckFailedException} (idempotent duplicate) is swallowed.
     */
    public void write(ProjectionEvent pe) {
        writeRoomMessages(pe);
        writeUserMessages(pe);
        writeUserRooms(pe);
    }

    /**
     * Write a stream batch more efficiently:
     * room/user message projections use BatchWriteItem, then successful records
     * advance user_rooms via conditional UpdateItem.
     */
    public BatchWriteResult writeBatch(List<ProjectionEvent> events) {
        if (events == null || events.isEmpty()) {
            return BatchWriteResult.success();
        }

        Set<String> failed = new HashSet<>();
        failed.addAll(batchWriteMessages(tableRoomMessages, events, ProjectionWriter::roomMessagesItem));
        failed.addAll(batchWriteMessages(tableUserMessages, events, ProjectionWriter::userMessagesItem));

        for (ProjectionEvent pe : events) {
            if (failed.contains(pe.messageId)) {
                continue;
            }
            try {
                writeUserRooms(pe);
            } catch (RuntimeException e) {
                failed.add(pe.messageId);
                log.warn("user_rooms write failed messageId={} userId={} roomId={}: {}",
                    pe.messageId, pe.userId, pe.roomId, e.getMessage());
            }
        }

        return BatchWriteResult.failed(failed);
    }

    // room_messages

    private void writeRoomMessages(ProjectionEvent pe) {
        Map<String, AttributeValue> item = roomMessagesItem(pe);
        try {
            client.putItem(PutItemRequest.builder()
                .tableName(tableRoomMessages)
                .item(item)
                .conditionExpression("attribute_not_exists(sk)")
                .build());
            log.debug("room_messages pk={} sk={}", pe.roomDayKey(), pe.sortKey());
        } catch (ConditionalCheckFailedException e) {
            log.debug("room_messages duplicate pk={} sk={} - skipping", pe.roomDayKey(), pe.sortKey());
        }
    }

    // user_messages

    private void writeUserMessages(ProjectionEvent pe) {
        Map<String, AttributeValue> item = userMessagesItem(pe);
        try {
            client.putItem(PutItemRequest.builder()
                .tableName(tableUserMessages)
                .item(item)
                .conditionExpression("attribute_not_exists(sk)")
                .build());
            log.debug("user_messages pk={} sk={}", pe.userDayKey(), pe.sortKey());
        } catch (ConditionalCheckFailedException e) {
            log.debug("user_messages duplicate pk={} sk={} - skipping", pe.userDayKey(), pe.sortKey());
        }
    }

    // user_rooms
    // UpdateItem: create row if absent, or advance lastActivityTs if this message is newer.

    private void writeUserRooms(ProjectionEvent pe) {
        Map<String, AttributeValue> key = new HashMap<>(2);
        key.put("userId", s(pe.userId));
        key.put("roomId", s(pe.roomId));

        Map<String, AttributeValue> values = new HashMap<>(2);
        values.put(":ts",       n(pe.eventTsMs));
        values.put(":username", s(pe.username));

        try {
            client.updateItem(UpdateItemRequest.builder()
                .tableName(tableUserRooms)
                .key(key)
                .updateExpression("SET lastActivityTs = :ts, username = :username")
                .conditionExpression("attribute_not_exists(userId) OR lastActivityTs < :ts")
                .expressionAttributeValues(values)
                .build());
            log.debug("user_rooms userId={} roomId={} lastActivityTs={}",
                pe.userId, pe.roomId, pe.eventTsMs);
        } catch (ConditionalCheckFailedException e) {
            // A newer (or equal) message already set lastActivityTs - safe to ignore
            log.debug("user_rooms userId={} roomId={} - no update (existing ts >= {})",
                pe.userId, pe.roomId, pe.eventTsMs);
        }
    }

    // Item builders (package-private for tests)

    static Map<String, AttributeValue> roomMessagesItem(ProjectionEvent pe) {
        Map<String, AttributeValue> item = new HashMap<>(12);
        item.put("pk",           s(pe.roomDayKey()));
        item.put("sk",           s(pe.sortKey()));
        item.put("messageId",    s(pe.messageId));
        item.put("roomId",       s(pe.roomId));
        item.put("userId",       s(pe.userId));
        item.put("username",     s(pe.username));
        item.put("message",      s(pe.message));
        item.put("messageType",  s(pe.messageType));
        item.put("roomSequence", n(pe.roomSequence));
        item.put("eventTsMs",    n(pe.eventTsMs));
        item.put("ingestedAtMs", n(pe.ingestedAtMs));
        return item;
    }

    static Map<String, AttributeValue> userMessagesItem(ProjectionEvent pe) {
        Map<String, AttributeValue> item = new HashMap<>(10);
        item.put("pk",           s(pe.userDayKey()));
        item.put("sk",           s(pe.sortKey()));
        item.put("messageId",    s(pe.messageId));
        item.put("roomId",       s(pe.roomId));
        item.put("userId",       s(pe.userId));
        item.put("username",     s(pe.username));
        item.put("message",      s(pe.message));
        item.put("messageType",  s(pe.messageType));
        item.put("eventTsMs",    n(pe.eventTsMs));
        item.put("ingestedAtMs", n(pe.ingestedAtMs));
        return item;
    }

    // Attribute helpers

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v != null ? v : "");
    }

    private static AttributeValue n(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    private Set<String> batchWriteMessages(
            String tableName,
            List<ProjectionEvent> events,
            Function<ProjectionEvent, Map<String, AttributeValue>> itemBuilder) {
        List<WriteRequest> requests = new ArrayList<>(events.size());
        for (ProjectionEvent pe : events) {
            requests.add(WriteRequest.builder()
                .putRequest(PutRequest.builder()
                    .item(itemBuilder.apply(pe))
                    .build())
                .build());
        }

        Set<String> failed = new HashSet<>();
        for (int start = 0; start < requests.size(); start += MAX_BATCH_WRITE_SIZE) {
            int end = Math.min(start + MAX_BATCH_WRITE_SIZE, requests.size());
            failed.addAll(batchWriteChunk(tableName, requests.subList(start, end)));
        }
        return failed;
    }

    private Set<String> batchWriteChunk(String tableName, List<WriteRequest> chunk) {
        List<WriteRequest> pending = new ArrayList<>(chunk);

        for (int attempt = 0; attempt < MAX_BATCH_WRITE_ATTEMPTS && !pending.isEmpty(); attempt++) {
            try {
                BatchWriteItemResponse response = client.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tableName, pending))
                    .build());
                pending = new ArrayList<>(response.unprocessedItems()
                    .getOrDefault(tableName, List.of()));
            } catch (RuntimeException e) {
                log.warn("BatchWriteItem failed table={} pending={} attempt={}: {}",
                    tableName, pending.size(), attempt + 1, e.getMessage());
            }

            if (!pending.isEmpty() && attempt + 1 < MAX_BATCH_WRITE_ATTEMPTS) {
                sleepQuietly(backoffMs(attempt));
            }
        }

        if (!pending.isEmpty()) {
            log.warn("BatchWriteItem exhausted retries table={} failedItems={}",
                tableName, pending.size());
        }
        return messageIds(pending);
    }

    private static Set<String> messageIds(List<WriteRequest> requests) {
        Set<String> ids = new HashSet<>(requests.size());
        for (WriteRequest request : requests) {
            Map<String, AttributeValue> item = request.putRequest() != null
                ? request.putRequest().item()
                : Map.of();
            AttributeValue messageId = item.get("messageId");
            if (messageId != null && messageId.s() != null) {
                ids.add(messageId.s());
            }
        }
        return ids;
    }

    private static long backoffMs(int attempt) {
        return BASE_BACKOFF_MS * (1L << attempt);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
