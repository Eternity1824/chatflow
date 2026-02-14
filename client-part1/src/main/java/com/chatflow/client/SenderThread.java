package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;
import com.chatflow.util.RateLimiter;
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
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

public class SenderThread implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(SenderThread.class);
    private static final int MAX_RETRIES = 5;
    private static final int INITIAL_BACKOFF_MS = 100;
    private static final long BACKPRESSURE_PARK_NS = 200_000L;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    private static final class BatchState {
        int pendingCount;
        int pendingBytes;
        long lastFlushNs;
        ChannelFuture lastWriteFuture;
        Channel channel;
    }

    private final BlockingQueue<MessageTemplate> messageQueue;
    private final int messagesToSend;
    private final ConnectionPool connectionPool;
    private final MetricsCollector metrics;
    private final CountDownLatch responseLatch;
    private final int batchSize;
    private final int maxBufferedBytes;
    private final long flushIntervalNs;
    private final boolean syncOnFlush;
    private final RateLimiter rateLimiter;

    public SenderThread(BlockingQueue<MessageTemplate> messageQueue,
                        int messagesToSend,
                        ConnectionPool connectionPool,
                        MetricsCollector metrics,
                        CountDownLatch responseLatch,
                        int batchSize,
                        int maxBufferedBytes,
                        int flushIntervalMs,
                        boolean syncOnFlush,
                        RateLimiter rateLimiter) {
        this.messageQueue = messageQueue;
        this.messagesToSend = messagesToSend;
        this.connectionPool = connectionPool;
        this.metrics = metrics;
        this.responseLatch = responseLatch;
        this.batchSize = Math.max(1, batchSize);
        this.maxBufferedBytes = Math.max(1024, maxBufferedBytes);
        this.flushIntervalNs = Math.max(0, flushIntervalMs) * 1_000_000L;
        this.syncOnFlush = syncOnFlush;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void run() {
        Map<String, BatchState> batches = new HashMap<>();
        try {
            int sentCount = 0;
            while (sentCount < messagesToSend) {
                MessageTemplate template = messageQueue.take();
                String roomId = template.getRoomId();
                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }

                boolean sent = false;
                for (int retry = 0; retry < MAX_RETRIES && !sent; retry++) {
                    try {
                        BatchState state = batches.computeIfAbsent(roomId, k -> {
                            BatchState s = new BatchState();
                            s.lastFlushNs = System.nanoTime();
                            return s;
                        });
                        Channel channel = getOrCreateChannel(roomId, state);
                        String channelId = channel.id().asShortText();
                        MDC.put("roomId", roomId);
                        MDC.put("channelId", channelId);
                        try {
                            long sendTimestampMs = System.currentTimeMillis();
                            ChatMessage message = new ChatMessage(
                                    template.getUserId(),
                                    template.getUsername(),
                                    template.getMessage(),
                                    Instant.ofEpochMilli(sendTimestampMs).toString(),
                                    template.getMessageType()
                            );

                            waitForWritable(channel);
                            String jsonMessage = objectMapper.writeValueAsString(message);
                            ChannelFuture writeFuture = channel.write(new TextWebSocketFrame(jsonMessage));
                            state.lastWriteFuture = writeFuture;
                            state.pendingCount++;
                            state.pendingBytes += jsonMessage.length();

                            boolean countFlush = state.pendingCount >= batchSize;
                            boolean bytesFlush = state.pendingBytes >= maxBufferedBytes;
                            boolean nonWritableFlush = !channel.isWritable();
                            boolean timeFlush = flushIntervalNs > 0
                                    && (System.nanoTime() - state.lastFlushNs) > flushIntervalNs;

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
                        BatchState state = batches.get(roomId);
                        if (state != null) {
                            state.channel = null;
                        }
                        connectionPool.removeConnection(roomId);
                        if (retry < MAX_RETRIES - 1) {
                            int baseBackoffMs = INITIAL_BACKOFF_MS * (1 << retry);
                            int jitteredBackoffMs = (int) (baseBackoffMs * (1.0 + random.nextDouble()));
                            Thread.sleep(jitteredBackoffMs);
                        }
                    }
                }

                if (!sent) {
                    MDC.put("roomId", roomId);
                    try {
                        logger.error("Failed to send message after {} retries", MAX_RETRIES);
                        metrics.recordFailure();
                        responseLatch.countDown();
                    } finally {
                        MDC.remove("roomId");
                    }
                }
                sentCount++;
            }

            for (Map.Entry<String, BatchState> entry : batches.entrySet()) {
                String roomId = entry.getKey();
                BatchState state = entry.getValue();
                if (state.pendingCount > 0) {
                    try {
                        Channel channel = getOrCreateChannel(roomId, state);
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

    private Channel getOrCreateChannel(String roomId, BatchState state) throws Exception {
        Channel channel = state.channel;
        if (channel != null && channel.isActive()) {
            return channel;
        }
        channel = connectionPool.getOrCreateConnection(roomId);
        state.channel = channel;
        return channel;
    }

    private void waitForWritable(Channel channel) {
        while (true) {
            if (channel.isWritable()) {
                return;
            }
            if (!channel.isActive()) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            LockSupport.parkNanos(BACKPRESSURE_PARK_NS);
        }
    }
}
