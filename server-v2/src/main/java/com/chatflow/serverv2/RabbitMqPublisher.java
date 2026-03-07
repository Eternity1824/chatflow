package com.chatflow.serverv2;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

public class RabbitMqPublisher implements AutoCloseable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(RabbitMqPublisher.class);

    private final RabbitMqConfig config;
    private final ChannelPool channelPool;
    private final PublishCircuitBreaker circuitBreaker;

    public RabbitMqPublisher(RabbitMqConfig config) throws IOException, TimeoutException {
        this.config = config;
        this.channelPool = new ChannelPool(config);
        this.channelPool.init();
        this.circuitBreaker = new PublishCircuitBreaker(
                config.getCircuitBreakerFailureThreshold(),
                config.getCircuitBreakerOpenDurationMs());
    }

    public void publish(QueueChatMessage message) throws Exception {
        if (config.isCircuitBreakerEnabled() && !circuitBreaker.allowRequest()) {
            logger.warn(
                    "Publish rejected by circuit breaker. state={} openUntil={}",
                    circuitBreaker.getState(),
                    circuitBreaker.getOpenUntilMs());
            throw new IllegalStateException("Queue temporarily unavailable (circuit breaker open)");
        }

        Channel channel = null;
        try {
            channel = channelPool.borrowChannel();
            byte[] payload = OBJECT_MAPPER.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
            String routingKey = "room." + message.getRoomId();
            AMQP.BasicProperties properties = MessageProperties.PERSISTENT_TEXT_PLAIN.builder()
                    .messageId(message.getMessageId())
                    .timestamp(new java.util.Date())
                    .type(message.getMessageType().name())
                    .build();
            channel.basicPublish(config.getExchangeName(), routingKey, true, properties, payload);
            channel.waitForConfirmsOrDie(5_000);
            if (config.isCircuitBreakerEnabled()) {
                circuitBreaker.recordSuccess();
            }
        } catch (Exception e) {
            if (config.isCircuitBreakerEnabled()) {
                circuitBreaker.recordFailure();
            }
            throw e;
        } finally {
            if (channel != null) {
                channelPool.returnChannel(channel);
            }
        }
    }

    @Override
    public void close() throws Exception {
        channelPool.close();
    }
}
