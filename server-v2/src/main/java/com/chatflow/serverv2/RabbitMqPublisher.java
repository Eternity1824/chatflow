package com.chatflow.serverv2;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

public class RabbitMqPublisher implements AutoCloseable {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RabbitMqConfig config;
    private final ChannelPool channelPool;

    public RabbitMqPublisher(RabbitMqConfig config) throws IOException, TimeoutException {
        this.config = config;
        this.channelPool = new ChannelPool(config);
        this.channelPool.init();
    }

    public void publish(QueueChatMessage message) throws Exception {
        Channel channel = channelPool.borrowChannel();
        try {
            byte[] payload = OBJECT_MAPPER.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
            String routingKey = "room." + message.getRoomId();
            AMQP.BasicProperties properties = MessageProperties.PERSISTENT_TEXT_PLAIN.builder()
                    .messageId(message.getMessageId())
                    .timestamp(new java.util.Date())
                    .type(message.getMessageType().name())
                    .build();
            channel.basicPublish(config.getExchangeName(), routingKey, true, properties, payload);
            channel.waitForConfirmsOrDie(5_000);
        } finally {
            channelPool.returnChannel(channel);
        }
    }

    @Override
    public void close() throws Exception {
        channelPool.close();
    }
}
