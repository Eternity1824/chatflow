package com.chatflow.consumer;

import com.chatflow.protocol.QueueChatMessage;

import java.util.concurrent.CompletableFuture;

public class RoomManager {
    private final BroadcastClient broadcastClient;
    private final ConsumerMetrics metrics;

    public RoomManager(BroadcastClient broadcastClient, ConsumerMetrics metrics) {
        this.broadcastClient = broadcastClient;
        this.metrics = metrics;
    }

    public boolean dispatch(QueueChatMessage message) {
        return dispatchAsync(message).join();
    }

    public CompletableFuture<Boolean> dispatchAsync(QueueChatMessage message) {
        return broadcastClient.broadcastAsync(message)
                .handle((delivered, error) -> {
                    boolean success = error == null && Boolean.TRUE.equals(delivered);
                    if (success) {
                        metrics.recordBroadcast(message.getRoomId());
                    } else {
                        metrics.recordBroadcastFailure();
                    }
                    return success;
                });
    }
}
