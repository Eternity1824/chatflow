package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public class SenderThread implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SenderThread.class);
    private static final int MAX_RETRIES = 5;
    private static final int INITIAL_BACKOFF_MS = 100;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final class BatchState {
        int pendingCount;
        int pendingBytes;
        long lastFlushNs;
        ChannelFuture lastWriteFuture;
    }

    private final BlockingQueue<MessageTemplate> messageQueue;
    private final int messagesToSend;
    private final ConnectionPool connectionPool;
    private final DetailedMetricsCollector metrics;
    private final int batchSize;
    private final int maxBufferedBytes;
    private final long flushIntervalNs;
    private final boolean syncOnFlush;

    public SenderThread(BlockingQueue<MessageTemplate> messageQueue,
                       int messagesToSend, ConnectionPool connectionPool,
                       DetailedMetricsCollector metrics,
                       int batchSize, int maxBufferedBytes,
                       int flushIntervalMs, boolean syncOnFlush) {
        this.messageQueue = messageQueue;
        this.messagesToSend = messagesToSend;
        this.connectionPool = connectionPool;
        this.metrics = metrics;
        this.batchSize = Math.max(1, batchSize);
        this.maxBufferedBytes = Math.max(1024, maxBufferedBytes);
        this.flushIntervalNs = Math.max(0, flushIntervalMs) * 1_000_000L;
        this.syncOnFlush = syncOnFlush;
    }

    @Override
    public void run() {
        Map<String, BatchState> batches = new HashMap<>();
        try {
            int sentCount = 0;
            while (sentCount < messagesToSend) {
                MessageTemplate template = messageQueue.take();
                String roomId = template.getRoomId();

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

                            ChatMessage message = new ChatMessage(
                                    template.getUserId(),
                                    template.getUsername(),
                                    template.getMessage(),
                                    Instant.now().toString(),
                                    template.getMessageType()
                            );
                            String jsonMessage = objectMapper.writeValueAsString(message);
                            ChannelFuture writeFuture = channel.write(new TextWebSocketFrame(jsonMessage));
                            state.lastWriteFuture = writeFuture;
                            state.pendingCount++;
                            state.pendingBytes += jsonMessage.length();
                            boolean countFlush = state.pendingCount >= batchSize;
                            boolean bytesFlush = state.pendingBytes >= maxBufferedBytes;
                            boolean nonWritableFlush = !channel.isWritable();
                            boolean timeFlush = flushIntervalNs > 0 &&
                                    (System.nanoTime() - state.lastFlushNs) > flushIntervalNs;

                            if (countFlush || bytesFlush || nonWritableFlush || timeFlush) {
                                channel.flush();
                                if (syncOnFlush && state.lastWriteFuture != null) {
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
                        metrics.recordFailure();
                    } finally {
                        MDC.remove("roomId");
                    }
                }
                sentCount++;
            }
            
            // Final flush for all rooms with pending messages
            for (Map.Entry<String, BatchState> entry : batches.entrySet()) {
                String roomId = entry.getKey();
                BatchState state = entry.getValue();
                if (state.pendingCount > 0) {
                    try {
                        Channel channel = connectionPool.getOrCreateConnection(roomId);
                        channel.flush();
                        if (syncOnFlush && state.lastWriteFuture != null) {
                            state.lastWriteFuture.sync();
                        }
                        logger.debug("Final flush for room {}: {} messages", roomId, state.pendingCount);
                    } catch (Exception e) {
                        logger.warn("Failed to final flush room {}", roomId, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Sender thread error", e);
        }
    }
}
