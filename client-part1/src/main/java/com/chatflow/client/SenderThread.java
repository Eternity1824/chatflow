package com.chatflow.client;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.concurrent.BlockingQueue;

public class SenderThread implements Runnable {
    private static final int MAX_RETRIES = 5;
    private static final int INITIAL_BACKOFF_MS = 100;

    private final BlockingQueue<String> messageQueue;
    private final int messagesToSend;
    private final ConnectionPool connectionPool;

    public SenderThread(BlockingQueue<String> messageQueue, 
                       int messagesToSend, ConnectionPool connectionPool) {
        this.messageQueue = messageQueue;
        this.messagesToSend = messagesToSend;
        this.connectionPool = connectionPool;
    }

    @Override
    public void run() {
        try {
            for (int i = 0; i < messagesToSend; i++) {
                String messageWithRoom = messageQueue.take();
                String[] parts = messageWithRoom.split("\\|");
                String jsonMessage = parts[0];
                String roomId = parts.length > 1 ? parts[1] : "1";

                boolean sent = false;
                for (int retry = 0; retry < MAX_RETRIES && !sent; retry++) {
                    try {
                        Channel channel = connectionPool.getOrCreateConnection(roomId);
                        channel.writeAndFlush(new TextWebSocketFrame(jsonMessage)).sync();
                        sent = true;

                    } catch (Exception e) {
                        System.err.println("Send failed (attempt " + (retry + 1) + "): " + e.getMessage());
                        connectionPool.removeConnection(roomId);
                        if (retry < MAX_RETRIES - 1) {
                            int backoffMs = INITIAL_BACKOFF_MS * (1 << retry);
                            Thread.sleep(backoffMs);
                        }
                    }
                }

                if (!sent) {
                    System.err.println("Failed to send message after " + MAX_RETRIES + " retries");
                }
            }
        } catch (Exception e) {
            System.err.println("Sender thread error: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
