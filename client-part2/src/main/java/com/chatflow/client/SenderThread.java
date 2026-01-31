package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public class SenderThread implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SenderThread.class);
    private static final int MAX_RETRIES = 5;
    private static final int INITIAL_BACKOFF_MS = 100;

    private static final int BATCH_SIZE = Integer.getInteger("chatflow.batch.size", 100);
    private static final int MAX_BUFFERED_BYTES = Integer.getInteger("chatflow.batch.max.bytes", 65536);
    private static final boolean SYNC_ON_FLUSH = Boolean.getBoolean("chatflow.flush.sync");

    private static final class BatchState {
        int pendingCount;
        int pendingBytes;
        long lastFlushNs;
        ChannelFuture lastWriteFuture;
    }

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
        Map<String, BatchState> batches = new HashMap<>();
        try {
            int sentCount = 0;
            while (sentCount < messagesToSend) {
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
                            BatchState state = batches.computeIfAbsent(roomId, k -> {
                                BatchState s = new BatchState();
                                s.lastFlushNs = System.nanoTime();
                                return s;
                            });

                            ChannelFuture writeFuture = channel.write(new TextWebSocketFrame(jsonMessage));
                            state.lastWriteFuture = writeFuture;
                            state.pendingCount++;
                            state.pendingBytes += jsonMessage.length();
                            boolean countFlush = state.pendingCount >= BATCH_SIZE;
                            boolean bytesFlush = state.pendingBytes >= MAX_BUFFERED_BYTES;
                            boolean nonWritableFlush = !channel.isWritable();

                            if (countFlush || bytesFlush || nonWritableFlush) {
                                channel.flush();
                                if (SYNC_ON_FLUSH && state.lastWriteFuture != null) {
                                    state.lastWriteFuture.sync();
                                }
                                state.pendingCount = 0;
                                state.pendingBytes = 0;
                                state.lastFlushNs = System.nanoTime();
                            }
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
