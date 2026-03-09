package com.chatflow.serverv2;

import com.chatflow.protocol.QueueChatMessage;
import com.chatflow.protocol.ProtobufConverter;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RabbitMqPublisher implements AutoCloseable {
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
        long sequenceNumber = -1L;
        try {
            channel = channelPool.borrowChannel();
            com.chatflow.protocol.proto.QueueChatMessage protoMessage = ProtobufConverter.toProto(message);
            byte[] payload = protoMessage.toByteArray();
            String routingKey = "room." + message.getRoomId();
            AMQP.BasicProperties properties = MessageProperties.PERSISTENT_BASIC.builder()
                    .messageId(message.getMessageId())
                    .timestamp(new java.util.Date())
                    .type(message.getMessageType().name())
                    .contentType("application/x-protobuf")
                    .build();
            sequenceNumber = channel.getNextPublishSeqNo();
            CompletableFuture<Void> confirmFuture = channelPool.registerPublishConfirm(channel, sequenceNumber);
            channel.basicPublish(config.getExchangeName(), routingKey, true, properties, payload);

            if (config.isPublisherConfirmAwait()) {
                awaitConfirm(confirmFuture, message.getMessageId());
            } else {
                observeConfirmAsync(confirmFuture, message.getMessageId());
            }
        } catch (Exception e) {
            if (channel != null && sequenceNumber > 0) {
                channelPool.failPublishConfirm(channel, sequenceNumber, e);
            }
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

    private void awaitConfirm(CompletableFuture<Void> confirmFuture, String messageId) throws Exception {
        try {
            confirmFuture.get(config.getPublisherConfirmTimeoutMs(), TimeUnit.MILLISECONDS);
            if (config.isCircuitBreakerEnabled()) {
                circuitBreaker.recordSuccess();
            }
        } catch (Exception e) {
            if (config.isCircuitBreakerEnabled()) {
                circuitBreaker.recordFailure();
            }
            throw new IOException("Publisher confirm failed for message " + messageId, e);
        }
    }

    private void observeConfirmAsync(CompletableFuture<Void> confirmFuture, String messageId) {
        confirmFuture.orTimeout(config.getPublisherConfirmTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((unused, error) -> {
                    if (config.isCircuitBreakerEnabled()) {
                        if (error == null) {
                            circuitBreaker.recordSuccess();
                        } else {
                            circuitBreaker.recordFailure();
                        }
                    }
                    if (error != null) {
                        logger.warn("Async publisher confirm failed for message {}", messageId, error);
                    }
                });
    }
}
