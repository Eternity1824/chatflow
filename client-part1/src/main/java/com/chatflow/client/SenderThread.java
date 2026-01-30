package com.chatflow.client;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.BlockingQueue;

public class SenderThread implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SenderThread.class);
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
                        String channelId = channel.id().asShortText();
                        MDC.put("roomId", roomId);
                        MDC.put("channelId", channelId);
                        try {
                            channel.writeAndFlush(new TextWebSocketFrame(jsonMessage)).sync();
                            sent = true;
                        } finally {
                            MDC.remove("roomId");
                            MDC.remove("channelId");
                        }

                    } catch (Exception e) {
                        MDC.put("roomId", roomId);
                        try {
                            logger.warn("Send failed (attempt {})", (retry + 1), e);
                        } finally {
                            MDC.remove("roomId");
                        }
                        connectionPool.removeConnection(roomId);
                        if (retry < MAX_RETRIES - 1) {
                            int backoffMs = INITIAL_BACKOFF_MS * (1 << retry);
                            Thread.sleep(backoffMs);
                        }
                    }
                }

                if (!sent) {
                    MDC.put("roomId", roomId);
                    try {
                        logger.error("Failed to send message after {} retries", MAX_RETRIES);
                    } finally {
                        MDC.remove("roomId");
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Sender thread error", e);
        }
    }

}
