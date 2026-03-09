package com.chatflow.serverv2;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeoutException;

public class ChannelPool implements AutoCloseable {
    private final BlockingQueue<Channel> pool;
    private final RabbitMqConfig config;
    private final ConcurrentHashMap<Channel, ConfirmTracker> confirmTrackers = new ConcurrentHashMap<>();
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
            ConfirmTracker tracker = confirmTrackers.remove(channel);
            if (tracker != null) {
                tracker.failAll(new IOException("Channel closed before borrow"));
            }
            return createChannel();
        }
        return channel;
    }

    public CompletableFuture<Void> registerPublishConfirm(Channel channel, long sequenceNumber) {
        ConfirmTracker tracker = confirmTrackers.get(channel);
        if (tracker == null) {
            throw new IllegalStateException("No confirm tracker for channel");
        }
        if (tracker.pendingCount() >= config.getPublisherConfirmMaxPending()) {
            throw new IllegalStateException("Too many pending publisher confirms on channel");
        }
        return tracker.register(sequenceNumber);
    }

    public void failPublishConfirm(Channel channel, long sequenceNumber, Throwable cause) {
        ConfirmTracker tracker = confirmTrackers.get(channel);
        if (tracker != null) {
            tracker.fail(sequenceNumber, cause);
        }
    }

    public void returnChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        if (!channel.isOpen()) {
            ConfirmTracker tracker = confirmTrackers.remove(channel);
            if (tracker != null) {
                tracker.failAll(new IOException("Channel closed before return"));
            }
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
        ConfirmTracker tracker = new ConfirmTracker();
        confirmTrackers.put(channel, tracker);
        channel.addConfirmListener(
                (deliveryTag, multiple) -> tracker.ack(deliveryTag, multiple),
                (deliveryTag, multiple) -> tracker.nack(deliveryTag, multiple));
        channel.addShutdownListener(cause ->
                tracker.failAll(new IOException("Rabbit channel shutdown: " + cause)));
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
            ConfirmTracker tracker = confirmTrackers.remove(channel);
            if (tracker != null) {
                tracker.failAll(new IOException("Channel pool closed"));
            }
            try {
                channel.close();
            } catch (TimeoutException ignored) {
            }
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    private static final class ConfirmTracker {
        private final ConcurrentSkipListMap<Long, CompletableFuture<Void>> pendingConfirms = new ConcurrentSkipListMap<>();

        private CompletableFuture<Void> register(long sequenceNumber) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            pendingConfirms.put(sequenceNumber, future);
            return future;
        }

        private int pendingCount() {
            return pendingConfirms.size();
        }

        private void ack(long deliveryTag, boolean multiple) {
            complete(deliveryTag, multiple, null);
        }

        private void nack(long deliveryTag, boolean multiple) {
            complete(deliveryTag, multiple, new IOException("RabbitMQ publisher confirm NACK"));
        }

        private void fail(long sequenceNumber, Throwable cause) {
            CompletableFuture<Void> future = pendingConfirms.remove(sequenceNumber);
            if (future != null) {
                future.completeExceptionally(cause);
            }
        }

        private void failAll(Throwable cause) {
            for (CompletableFuture<Void> future : pendingConfirms.values()) {
                future.completeExceptionally(cause);
            }
            pendingConfirms.clear();
        }

        private void complete(long deliveryTag, boolean multiple, Throwable error) {
            if (multiple) {
                NavigableMap<Long, CompletableFuture<Void>> confirmed = pendingConfirms.headMap(deliveryTag, true);
                if (confirmed.isEmpty()) {
                    return;
                }
                for (CompletableFuture<Void> future : new ArrayList<>(confirmed.values())) {
                    completeFuture(future, error);
                }
                confirmed.clear();
                return;
            }

            CompletableFuture<Void> future = pendingConfirms.remove(deliveryTag);
            if (future != null) {
                completeFuture(future, error);
            }
        }

        private void completeFuture(CompletableFuture<Void> future, Throwable error) {
            if (error == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(error);
            }
        }
    }
}
