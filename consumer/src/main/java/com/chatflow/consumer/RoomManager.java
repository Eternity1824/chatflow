package com.chatflow.consumer;

import com.chatflow.protocol.QueueChatMessage;

public class RoomManager {
    private final BroadcastClient broadcastClient;
    private final ConsumerMetrics metrics;

    public RoomManager(BroadcastClient broadcastClient, ConsumerMetrics metrics) {
        this.broadcastClient = broadcastClient;
        this.metrics = metrics;
    }

    public boolean dispatch(QueueChatMessage message) {
        boolean delivered = broadcastClient.broadcast(message);
        if (delivered) {
            metrics.recordBroadcast(message.getRoomId());
        } else {
            metrics.recordBroadcastFailure();
        }
        return delivered;
    }
}
