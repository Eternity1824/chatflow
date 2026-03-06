package com.chatflow.consumer;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.HashMap;
import java.util.Map;

public final class RabbitTopologyInitializer {
    private RabbitTopologyInitializer() {
    }

    public static void initialize(ConsumerConfig config) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getRabbitHost());
        factory.setPort(config.getRabbitPort());
        factory.setUsername(config.getRabbitUsername());
        factory.setPassword(config.getRabbitPassword());
        factory.setVirtualHost(config.getRabbitVirtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);

        try (Connection connection = factory.newConnection("chatflow-consumer-topology");
             Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(config.getExchangeName(), BuiltinExchangeType.TOPIC, true);

            Map<String, Object> arguments = new HashMap<>();
            if (config.getQueueMessageTtlMs() > 0) {
                arguments.put("x-message-ttl", config.getQueueMessageTtlMs());
            }
            if (config.getQueueMaxLength() > 0) {
                arguments.put("x-max-length", config.getQueueMaxLength());
            }
            Map<String, Object> queueArgs = arguments.isEmpty() ? null : arguments;

            for (int roomId = config.getRoomStart(); roomId <= config.getRoomEnd(); roomId++) {
                String queueName = config.queueNameForRoom(roomId);
                channel.queueDeclare(queueName, true, false, false, queueArgs);
                channel.queueBind(queueName, config.getExchangeName(), queueName);
            }
        }
    }
}
