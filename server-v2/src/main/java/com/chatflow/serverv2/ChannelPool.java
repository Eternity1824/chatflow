package com.chatflow.serverv2;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.HashMap;
import java.util.Map;

public class ChannelPool implements AutoCloseable {
    private final BlockingQueue<Channel> pool;
    private final RabbitMqConfig config;
    private Connection connection;

    public ChannelPool(RabbitMqConfig config) {
        this.config = config;
        this.pool = new ArrayBlockingQueue<>(config.getChannelPoolSize());
    }

    public void init() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.getHost());
        factory.setPort(config.getPort());
        factory.setUsername(config.getUsername());
        factory.setPassword(config.getPassword());
        factory.setVirtualHost(config.getVirtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);

        connection = factory.newConnection("chatflow-server-v2");
        for (int i = 0; i < config.getChannelPoolSize(); i++) {
            pool.offer(createChannel());
        }
    }

    public Channel borrowChannel() throws InterruptedException, IOException {
        Channel channel = pool.take();
        if (!channel.isOpen()) {
            return createChannel();
        }
        return channel;
    }

    public void returnChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        if (!channel.isOpen()) {
            try {
                pool.offer(createChannel());
            } catch (IOException e) {
                return;
            }
            return;
        }
        pool.offer(channel);
    }

    private Channel createChannel() throws IOException {
        Channel channel = connection.createChannel();
        channel.confirmSelect();
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
            String queueName = "room." + roomId;
            channel.queueDeclare(queueName, true, false, false, queueArgs);
            channel.queueBind(queueName, config.getExchangeName(), queueName);
        }
        return channel;
    }

    @Override
    public void close() throws Exception {
        Channel channel;
        while ((channel = pool.poll()) != null) {
            try {
                channel.close();
            } catch (TimeoutException ignored) {
            }
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }
}
