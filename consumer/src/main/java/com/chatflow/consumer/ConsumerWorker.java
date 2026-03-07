package com.chatflow.consumer;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
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
    }

    @Override
    public void run() {
        logger.info("Consumer worker {} started with queues {}", workerId, queues);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ensureChannel();
                boolean hasMessage = false;
                for (String queue : queues) {
                    if (!running.get()) {
                        break;
                    }
                    hasMessage |= pollQueue(queue);
                }
                if (!hasMessage) {
                    Thread.sleep(config.getPollIntervalMs());
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
        closeResources();
        logger.info("Consumer worker {} stopped", workerId);
    }

    private void ensureChannel() throws Exception {
        if (channel != null && channel.isOpen() && connection != null && connection.isOpen()) {
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
    }

    private boolean pollQueue(String queueName) throws Exception {
        GetResponse response = channel.basicGet(queueName, false);
        if (response == null) {
            return false;
        }
        metrics.recordPolled();
        long deliveryTag = response.getEnvelope().getDeliveryTag();
        QueueChatMessage message;
        try {
            message = OBJECT_MAPPER.readValue(response.getBody(), QueueChatMessage.class);
        } catch (Exception e) {
            metrics.recordParseError();
            channel.basicAck(deliveryTag, false);
            metrics.recordAcked();
            logger.warn("Worker {} dropped malformed payload from queue {}", workerId, queueName, e);
            return true;
        }

        metrics.recordDedupeCheck();
        if (deduplicator.isDuplicate(message.getMessageId())) {
            metrics.recordDuplicate();
            channel.basicAck(deliveryTag, false);
            metrics.recordAcked();
            return true;
        }

        if (message.getRoomSequence() == null && message.getRoomId() != null && !message.getRoomId().isBlank()) {
            long sequence = roomSequenceManager.nextSequence(message.getRoomId());
            message.setRoomSequence(sequence);
        }

        boolean delivered = roomManager.dispatch(message);
        if (delivered) {
            channel.basicAck(deliveryTag, false);
            metrics.recordAcked();
            return true;
        }

        int retryCount = extractRetryCount(response.getProps().getHeaders());
        if (retryCount < config.getMaxRetries()) {
            sleepRetryBackoff(retryCount + 1);
            republishWithRetry(message, retryCount + 1);
            channel.basicAck(deliveryTag, false);
            metrics.recordAcked();
            metrics.recordRetried();
        } else {
            channel.basicNack(deliveryTag, false, false);
            metrics.recordRetriesExhausted();
            metrics.recordDropped();
        }
        return true;
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
            try {
                channel.close();
            } catch (Exception ignored) {
            } finally {
                channel = null;
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            } finally {
                connection = null;
            }
        }
    }

    private void sleepQuietly(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
