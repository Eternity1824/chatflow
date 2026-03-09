package com.chatflow.consumer;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConsumerWorker implements Runnable, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerWorker.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RETRY_HEADER = "x-chatflow-retry";

    private final int workerId;
    private final List<String> queues;
    private final ConsumerConfig config;
    private final RoomManager roomManager;
    private final RoomSequenceManager roomSequenceManager;
    private final MessageDeduplicator deduplicator;
    private final ConsumerMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BlockingQueue<DeliveryEnvelope> deliveryQueue;
    private final BlockingQueue<AckAction> ackQueue;
    private final Semaphore inFlightSemaphore;
    private final int maxInFlight;
    private final Map<String, Semaphore> queueSemaphores = new HashMap<>();

    private final List<String> consumerTags = new ArrayList<>();

    private Connection connection;
    private Channel channel;

    public ConsumerWorker(
            int workerId,
            List<String> queues,
            ConsumerConfig config,
            RoomManager roomManager,
            RoomSequenceManager roomSequenceManager,
            MessageDeduplicator deduplicator,
            ConsumerMetrics metrics) {
        this.workerId = workerId;
        this.queues = queues;
        this.config = config;
        this.roomManager = roomManager;
        this.roomSequenceManager = roomSequenceManager;
        this.deduplicator = deduplicator;
        this.metrics = metrics;

        int prefetch = Math.max(1, config.getPrefetchCount());
        int queueCount = Math.max(1, queues.size());
        this.maxInFlight = Math.max(1, config.getGlobalMaxInFlight());
        int queueCapacity = Math.max(256, prefetch * queueCount * 2);
        this.deliveryQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.ackQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.inFlightSemaphore = new Semaphore(maxInFlight);
        int roomMaxInFlight = Math.max(1, config.getRoomMaxInFlight());
        for (String queueName : queues) {
            this.queueSemaphores.put(queueName, new Semaphore(roomMaxInFlight));
        }
    }

    @Override
    public void run() {
        logger.info(
                "Consumer worker {} started with queues {} in push mode (prefetch={}, roomMaxInFlight={}, globalMaxInFlight={})",
                workerId,
                queues,
                config.getPrefetchCount(),
                config.getRoomMaxInFlight(),
                maxInFlight);

        if (queues == null || queues.isEmpty()) {
            logger.info("Consumer worker {} has no assigned queues, entering idle mode", workerId);
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                sleepQuietly(1_000L);
            }
            logger.info("Consumer worker {} stopped", workerId);
            return;
        }

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ensureChannelAndConsumers();
                drainAckQueue(256);

                DeliveryEnvelope delivery = deliveryQueue.poll(
                        Math.max(1L, config.getPollIntervalMs()),
                        TimeUnit.MILLISECONDS);
                if (delivery != null) {
                    handleDelivery(delivery);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.warn("Worker {} encountered an error. Reconnecting...", workerId, e);
                closeResources();
                sleepQuietly(1_000L);
            }
        }

        waitForInFlight(3_000L);
        try {
            drainAckQueue(Integer.MAX_VALUE);
        } catch (Exception e) {
            logger.warn("Worker {} failed while draining ack queue during shutdown", workerId, e);
        }
        closeResources();
        logger.info("Consumer worker {} stopped", workerId);
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
            DeliveryEnvelope envelope = new DeliveryEnvelope(
                    queueName,
                    delivery.getEnvelope().getDeliveryTag(),
                    delivery.getBody(),
                    delivery.getProperties());

            while (running.get()) {
                try {
                    if (deliveryQueue.offer(envelope, 200, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        };
    }

    private CancelCallback createCancelCallback(String queueName) {
        return consumerTag -> logger.warn(
                "Worker {} consumer canceled for queue {} (tag={})",
                workerId,
                queueName,
                consumerTag);
    }

    private void handleDelivery(DeliveryEnvelope delivery) throws Exception {
        metrics.recordPolled();

        QueueChatMessage message;
        try {
            message = OBJECT_MAPPER.readValue(delivery.body, QueueChatMessage.class);
        } catch (Exception e) {
            metrics.recordParseError();
            basicAck(delivery.deliveryTag);
            logger.warn("Worker {} dropped malformed payload from queue {}", workerId, delivery.queueName, e);
            return;
        }

        metrics.recordDedupeCheck();
        if (deduplicator.isDuplicate(message.getMessageId())) {
            metrics.recordDuplicate();
            basicAck(delivery.deliveryTag);
            return;
        }

        if (message.getRoomSequence() == null && message.getRoomId() != null && !message.getRoomId().isBlank()) {
            long sequence = roomSequenceManager.nextSequence(message.getRoomId());
            message.setRoomSequence(sequence);
        }

        int retryCount = extractRetryCount(delivery.properties.getHeaders());
        Semaphore queueSemaphore = queueSemaphores.get(delivery.queueName);
        if (queueSemaphore != null && !queueSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            channel.basicNack(delivery.deliveryTag, false, true);
            return;
        }

        if (!inFlightSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            if (queueSemaphore != null) {
                queueSemaphore.release();
            }
            channel.basicNack(delivery.deliveryTag, false, true);
            return;
        }

        roomManager.dispatchAsync(message)
                .whenComplete((delivered, error) -> {
                    boolean success = error == null && Boolean.TRUE.equals(delivered);
                    AckAction action = new AckAction(delivery.deliveryTag, message, retryCount, success);
                    enqueueAckAction(action);
                    inFlightSemaphore.release();
                    if (queueSemaphore != null) {
                        queueSemaphore.release();
                    }
                });
    }

    private void enqueueAckAction(AckAction action) {
        while (running.get()) {
            try {
                if (ackQueue.offer(action, 200, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void drainAckQueue(int maxItems) throws Exception {
        int processed = 0;
        while (processed < maxItems) {
            AckAction action = ackQueue.poll();
            if (action == null) {
                return;
            }
            processAckAction(action);
            processed++;
        }
    }

    private void processAckAction(AckAction action) throws Exception {
        if (action.delivered) {
            basicAck(action.deliveryTag);
            return;
        }

        if (action.retryCount < config.getMaxRetries()) {
            int nextRetry = action.retryCount + 1;
            sleepRetryBackoff(nextRetry);
            republishWithRetry(action.message, nextRetry);
            basicAck(action.deliveryTag);
            metrics.recordRetried();
            return;
        }

        channel.basicNack(action.deliveryTag, false, false);
        metrics.recordRetriesExhausted();
        metrics.recordDropped();
    }

    private void basicAck(long deliveryTag) throws Exception {
        channel.basicAck(deliveryTag, false);
        metrics.recordAcked();
    }

    private void republishWithRetry(QueueChatMessage message, int retryCount) throws Exception {
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

    private void waitForInFlight(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (inFlightSemaphore.availablePermits() >= maxInFlight) {
                return;
            }
            sleepQuietly(50L);
        }
    }

    private void sleepRetryBackoff(int attempt) {
        long base = Math.max(1L, config.getRetryBackoffBaseMs());
        long max = Math.max(base, config.getRetryBackoffMaxMs());
        long backoff = base * (1L << Math.min(20, Math.max(0, attempt - 1)));
        long cappedBackoff = Math.min(max, backoff);
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, cappedBackoff / 4));
        long totalSleep = Math.min(max, cappedBackoff + jitter);
        sleepQuietly(totalSleep);
    }

    public void shutdown() {
        running.set(false);
        closeResources();
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

        deliveryQueue.clear();
        ackQueue.clear();
        int missingPermits = maxInFlight - inFlightSemaphore.availablePermits();
        if (missingPermits > 0) {
            inFlightSemaphore.release(missingPermits);
        }
    }

    private void sleepQuietly(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class DeliveryEnvelope {
        private final String queueName;
        private final long deliveryTag;
        private final byte[] body;
        private final AMQP.BasicProperties properties;

        private DeliveryEnvelope(String queueName, long deliveryTag, byte[] body, AMQP.BasicProperties properties) {
            this.queueName = queueName;
            this.deliveryTag = deliveryTag;
            this.body = body;
            this.properties = properties;
        }
    }

    private static final class AckAction {
        private final long deliveryTag;
        private final QueueChatMessage message;
        private final int retryCount;
        private final boolean delivered;

        private AckAction(long deliveryTag, QueueChatMessage message, int retryCount, boolean delivered) {
            this.deliveryTag = deliveryTag;
            this.message = message;
            this.retryCount = retryCount;
            this.delivered = delivered;
        }
    }
}
