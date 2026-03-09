package com.chatflow.consumer;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import io.netty.channel.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NettyConsumerWorker implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NettyConsumerWorker.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RETRY_HEADER = "x-chatflow-retry";

    private final int workerId;
    private final List<String> queues;
    private final ConsumerConfig config;
    private final RoomManager roomManager;
    private final RoomSequenceManager roomSequenceManager;
    private final MessageDeduplicator deduplicator;
    private final ConsumerMetrics metrics;
    private final EventLoop eventLoop;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger globalInFlight = new AtomicInteger(0);
    private final Map<String, AtomicInteger> roomInFlight = new HashMap<>();
    private final Map<String, ArrayDeque<PendingDelivery>> pendingDeliveries = new HashMap<>();
    private final List<String> consumerTags = new ArrayList<>();
    private int nextQueueCursor = 0;

    private Connection connection;
    private Channel channel;

    public NettyConsumerWorker(
            int workerId,
            List<String> queues,
            ConsumerConfig config,
            RoomManager roomManager,
            RoomSequenceManager roomSequenceManager,
            MessageDeduplicator deduplicator,
            ConsumerMetrics metrics,
            EventLoop eventLoop) {
        this.workerId = workerId;
        this.queues = queues;
        this.config = config;
        this.roomManager = roomManager;
        this.roomSequenceManager = roomSequenceManager;
        this.deduplicator = deduplicator;
        this.metrics = metrics;
        this.eventLoop = eventLoop;

        for (String queueName : queues) {
            this.roomInFlight.put(queueName, new AtomicInteger(0));
            this.pendingDeliveries.put(queueName, new ArrayDeque<>());
        }
    }

    public void start() {
        if (queues == null || queues.isEmpty()) {
            logger.info("Consumer worker {} has no assigned queues, skipping start", workerId);
            return;
        }

        eventLoop.execute(() -> {
            try {
                ensureChannelAndConsumers();
                logger.info(
                        "Consumer worker {} started on EventLoop with queues {} (prefetch={}, roomMaxInFlight={}, globalMaxInFlight={})",
                        workerId,
                        queues,
                        config.getPrefetchCount(),
                        config.getRoomMaxInFlight(),
                        config.getGlobalMaxInFlight());
            } catch (Exception e) {
                logger.error("Worker {} failed to start", workerId, e);
                scheduleReconnect();
            }
        });
    }

    private void ensureChannelAndConsumers() throws Exception {
        if (channel != null && channel.isOpen() && connection != null && connection.isOpen() && !consumerTags.isEmpty()) {
            return;
        }

        closeResources();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getRabbitHost());
        factory.setPort(config.getRabbitPort());
        factory.setUsername(config.getRabbitUsername());
        factory.setPassword(config.getRabbitPassword());
        factory.setVirtualHost(config.getRabbitVirtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);

        connection = factory.newConnection("chatflow-consumer-worker-" + workerId);
        channel = connection.createChannel();
        channel.basicQos(Math.max(1, config.getPrefetchCount()));

        for (String queueName : queues) {
            String consumerTag = channel.basicConsume(
                    queueName,
                    false,
                    createDeliverCallback(queueName),
                    createCancelCallback(queueName));
            consumerTags.add(consumerTag);
        }
    }

    private DeliverCallback createDeliverCallback(String queueName) {
        return (consumerTag, delivery) -> {
            if (!running.get()) {
                return;
            }

            eventLoop.execute(() -> {
                if (!running.get()) {
                    return;
                }
                handleDelivery(queueName, delivery.getEnvelope().getDeliveryTag(), delivery.getBody(), delivery.getProperties());
            });
        };
    }

    private CancelCallback createCancelCallback(String queueName) {
        return consumerTag -> {
            logger.warn("Worker {} consumer canceled for queue {} (tag={})", workerId, queueName, consumerTag);
            eventLoop.execute(this::scheduleReconnect);
        };
    }

    private void handleDelivery(String queueName, long deliveryTag, byte[] body, AMQP.BasicProperties properties) {
        metrics.recordPolled();

        QueueChatMessage message;
        try {
            message = OBJECT_MAPPER.readValue(body, QueueChatMessage.class);
        } catch (Exception e) {
            metrics.recordParseError();
            basicAck(deliveryTag);
            logger.warn("Worker {} dropped malformed payload from queue {}", workerId, queueName, e);
            return;
        }

        metrics.recordDedupeCheck();
        if (deduplicator.isDuplicate(message.getMessageId())) {
            metrics.recordDuplicate();
            basicAck(deliveryTag);
            return;
        }

        if (message.getRoomSequence() == null && message.getRoomId() != null && !message.getRoomId().isBlank()) {
            long sequence = roomSequenceManager.nextSequence(message.getRoomId());
            message.setRoomSequence(sequence);
        }

        int retryCount = extractRetryCount(properties.getHeaders());
        PendingDelivery pending = new PendingDelivery(queueName, deliveryTag, message, retryCount);
        enqueueAndDrain(pending);
    }

    private void enqueueAndDrain(PendingDelivery pending) {
        ArrayDeque<PendingDelivery> queue = pendingDeliveries.get(pending.queueName);
        if (queue == null) {
            basicNack(pending.deliveryTag, true);
            return;
        }
        queue.offerLast(pending);
        drainPendingDeliveries();
    }

    private void drainPendingDeliveries() {
        if (queues.isEmpty()) {
            return;
        }

        int idlePasses = 0;
        while (running.get() && idlePasses < queues.size()) {
            String queueName = queues.get(nextQueueCursor);
            nextQueueCursor = (nextQueueCursor + 1) % queues.size();

            ArrayDeque<PendingDelivery> queue = pendingDeliveries.get(queueName);
            if (queue == null || queue.isEmpty()) {
                idlePasses++;
                continue;
            }

            if (!tryAcquireCapacity(queueName)) {
                idlePasses++;
                continue;
            }

            PendingDelivery pending = queue.pollFirst();
            dispatchPending(pending);
            idlePasses = 0;
        }
    }

    private boolean tryAcquireCapacity(String queueName) {
        AtomicInteger roomCounter = roomInFlight.get(queueName);
        if (roomCounter != null && roomCounter.get() >= config.getRoomMaxInFlight()) {
            return false;
        }
        if (globalInFlight.get() >= config.getGlobalMaxInFlight()) {
            return false;
        }
        if (roomCounter != null) {
            roomCounter.incrementAndGet();
        }
        globalInFlight.incrementAndGet();
        return true;
    }

    private void dispatchPending(PendingDelivery pending) {
        roomManager.dispatchAsync(pending.message)
                .whenComplete((delivered, error) -> eventLoop.execute(() -> {
                    releaseCapacity(pending.queueName);
                    boolean success = error == null && Boolean.TRUE.equals(delivered);
                    processAckAction(pending.deliveryTag, pending.message, pending.retryCount, success);
                    drainPendingDeliveries();
                }));
    }

    private void releaseCapacity(String queueName) {
        AtomicInteger roomCounter = roomInFlight.get(queueName);
        if (roomCounter != null) {
            roomCounter.decrementAndGet();
        }
        globalInFlight.decrementAndGet();
    }

    private void processAckAction(long deliveryTag, QueueChatMessage message, int retryCount, boolean delivered) {
        if (delivered) {
            basicAck(deliveryTag);
            return;
        }

        if (retryCount < config.getMaxRetries()) {
            int nextRetry = retryCount + 1;
            long backoffMs = computeRetryBackoffMs(nextRetry);
            eventLoop.schedule(() -> {
                republishWithRetry(message, nextRetry);
                basicAck(deliveryTag);
                metrics.recordRetried();
            }, backoffMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }

        basicNack(deliveryTag, false);
        metrics.recordRetriesExhausted();
        metrics.recordDropped();
    }

    private void basicAck(long deliveryTag) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.basicAck(deliveryTag, false);
                metrics.recordAcked();
            }
        } catch (Exception e) {
            logger.warn("Worker {} failed to ack delivery {}", workerId, deliveryTag, e);
        }
    }

    private void basicNack(long deliveryTag, boolean requeue) {
        try {
            if (channel != null && channel.isOpen()) {
                channel.basicNack(deliveryTag, false, requeue);
            }
        } catch (Exception e) {
            logger.warn("Worker {} failed to nack delivery {}", workerId, deliveryTag, e);
        }
    }

    private void republishWithRetry(QueueChatMessage message, int retryCount) {
        try {
            if (channel == null || !channel.isOpen()) {
                return;
            }

            Map<String, Object> headers = new HashMap<>();
            headers.put(RETRY_HEADER, retryCount);

            byte[] payload = OBJECT_MAPPER.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .messageId(message.getMessageId())
                    .timestamp(new java.util.Date())
                    .type(message.getMessageType() != null ? message.getMessageType().name() : "UNKNOWN")
                    .contentType("application/json")
                    .deliveryMode(2)
                    .headers(headers)
                    .build();

            channel.basicPublish(
                    config.getExchangeName(),
                    "room." + message.getRoomId(),
                    true,
                    properties,
                    payload);
        } catch (Exception e) {
            logger.warn("Worker {} failed to republish message {}", workerId, message.getMessageId(), e);
        }
    }

    private int extractRetryCount(Map<String, Object> headers) {
        if (headers == null || headers.isEmpty()) {
            return 0;
        }
        Object rawValue = headers.get(RETRY_HEADER);
        if (rawValue == null) {
            return 0;
        }
        if (rawValue instanceof Number) {
            return ((Number) rawValue).intValue();
        }
        try {
            return Integer.parseInt(rawValue.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private long computeRetryBackoffMs(int attempt) {
        long base = Math.max(1L, config.getRetryBackoffBaseMs());
        long max = Math.max(base, config.getRetryBackoffMaxMs());
        long backoff = base * (1L << Math.min(20, Math.max(0, attempt - 1)));
        long cappedBackoff = Math.min(max, backoff);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, cappedBackoff / 4));
        return Math.min(max, cappedBackoff + jitter);
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        closeResources();
        eventLoop.schedule(() -> {
            if (running.get()) {
                try {
                    ensureChannelAndConsumers();
                } catch (Exception e) {
                    logger.warn("Worker {} reconnect failed, will retry", workerId, e);
                    scheduleReconnect();
                }
            }
        }, 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        running.set(false);
        eventLoop.execute(this::closeResources);
    }

    @Override
    public void close() {
        shutdown();
    }

    private void closeResources() {
        if (channel != null) {
            for (String consumerTag : consumerTags) {
                try {
                    channel.basicCancel(consumerTag);
                } catch (Exception ignored) {
                }
            }
            consumerTags.clear();

            try {
                channel.close();
            } catch (Exception ignored) {
            } finally {
                channel = null;
            }
        } else {
            consumerTags.clear();
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            } finally {
                connection = null;
            }
        }

        globalInFlight.set(0);
        for (AtomicInteger counter : roomInFlight.values()) {
            counter.set(0);
        }
        for (ArrayDeque<PendingDelivery> queue : pendingDeliveries.values()) {
            queue.clear();
        }
    }

    private static final class PendingDelivery {
        private final String queueName;
        private final long deliveryTag;
        private final QueueChatMessage message;
        private final int retryCount;

        private PendingDelivery(String queueName, long deliveryTag, QueueChatMessage message, int retryCount) {
            this.queueName = queueName;
            this.deliveryTag = deliveryTag;
            this.message = message;
            this.retryCount = retryCount;
        }
    }
}
