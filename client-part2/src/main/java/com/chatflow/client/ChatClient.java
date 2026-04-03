package com.chatflow.client;

import com.chatflow.util.RateLimiter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatClient {
    private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);

    private final String serverUrl;
    private final DetailedMetricsCollector metrics;
    private final EventLoopGroup sharedEventLoopGroup;
    private final ChannelFactory<? extends Channel> channelFactory;
    private final ConnectionPool connectionPool;
    private final CountDownLatch responseLatch;
    private final int totalMessages;
    private final int warmupThreads;
    private final int warmupMessagesPerThread;
    private final int warmupTotal;
    private final int mainPhaseMessages;
    private final int responseWaitSeconds;
    private final int batchSize;
    private final int batchMaxBytes;
    private final int flushIntervalMs;
    private final boolean flushSync;
    private final int connectionsPerRoom;
    private final int mainThreadsOverride;
    private final int targetQps;
    private final RateLimiter rateLimiter;

    public ChatClient(ClientConfig.ClientSettings settings, String serverUrlOverride) {
        this.serverUrl = serverUrlOverride != null ? serverUrlOverride : settings.getServerUrl();
        this.totalMessages = settings.getTotalMessages();
        this.warmupThreads = settings.getWarmupThreads();
        this.warmupMessagesPerThread = settings.getWarmupMessagesPerThread();
        this.warmupTotal = warmupThreads * warmupMessagesPerThread;
        this.mainPhaseMessages = totalMessages - warmupTotal;
        this.responseWaitSeconds = settings.getResponseWaitSeconds();
        this.batchSize = settings.getBatchSize();
        this.batchMaxBytes = settings.getBatchMaxBytes();
        this.flushIntervalMs = settings.getFlushIntervalMs();
        this.flushSync = settings.isFlushSync();
        this.connectionsPerRoom = settings.getConnectionsPerRoom();
        this.mainThreadsOverride = settings.getMainThreads();
        this.targetQps = settings.getTargetQps();
        this.rateLimiter = targetQps > 0 ? RateLimiter.create(targetQps) : null;
        EventLoopGroup eventLoopGroup;
        ChannelFactory<? extends Channel> socketChannelFactory;
        if (Epoll.isAvailable()) {
            eventLoopGroup = new EpollEventLoopGroup();
            socketChannelFactory = EpollSocketChannel::new;
            logger.info("Using EpollEventLoopGroup");
        } else if (KQueue.isAvailable()) {
            eventLoopGroup = new KQueueEventLoopGroup();
            socketChannelFactory = KQueueSocketChannel::new;
            logger.info("Using KQueueEventLoopGroup");
        } else {
            eventLoopGroup = new NioEventLoopGroup();
            socketChannelFactory = NioSocketChannel::new;
            logger.info("Using NioEventLoopGroup");
        }
        this.sharedEventLoopGroup = eventLoopGroup;
        this.channelFactory = socketChannelFactory;
        this.responseLatch = new CountDownLatch(totalMessages);
        try {
            this.metrics = new DetailedMetricsCollector(totalMessages, responseLatch, "results/metrics.csv");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize metrics collector", e);
        }
        this.connectionPool = new ConnectionPool(serverUrl, sharedEventLoopGroup, metrics,
                settings.getConnectionsPerRoom(), settings.getHandshakeTimeoutSeconds(),
                settings.getMaxConcurrentHandshakes(), settings.getHandshakeRetryDelayMs(),
                channelFactory);
    }

    public void run() throws InterruptedException {
        logger.info("Starting ChatFlow Client");
        logger.info("Server URL: {}", serverUrl);
        logger.info("Total messages to send: {}", totalMessages);
        logger.info("Config: warmupThreads={}, warmupMessagesPerThread={}, connectionsPerRoom={}",
                warmupThreads, warmupMessagesPerThread, connectionsPerRoom);
        logger.info("Config: batchSize={}, batchMaxBytes={}, flushIntervalMs={}, flushSync={}",
                batchSize, batchMaxBytes, flushIntervalMs, flushSync);
        String mainThreadsLabel = mainThreadsOverride > 0 ? String.valueOf(mainThreadsOverride) : "auto";
        String targetQpsLabel = targetQps > 0 ? String.valueOf(targetQps) : "unlimited";
        logger.info("Config: mainThreads={}, targetQps={}", mainThreadsLabel, targetQpsLabel);

        BlockingQueue<MessageTemplate> messageQueue = new ArrayBlockingQueue<>(100_000);

        Thread generatorThread = new Thread(new MessageGenerator(messageQueue, totalMessages, 20));
        generatorThread.start();

        long testStartMs = System.currentTimeMillis();
        metrics.markStart();

        logger.info("=== Warmup Phase ===");
        logger.info("Starting {} threads, each sending {} messages", warmupThreads, warmupMessagesPerThread);
        long warmupStart = System.currentTimeMillis();
        runPhase(messageQueue, warmupThreads, warmupMessagesPerThread);
        long warmupEnd = System.currentTimeMillis();
        logger.info("Warmup completed in {} ms", (warmupEnd - warmupStart));

        logger.info("=== Main Phase ===");
        int mainThreads = mainThreadsOverride > 0 ? mainThreadsOverride : calculateOptimalThreads();
        if (mainThreads < 1) {
            mainThreads = 1;
        }
        int messagesPerThread = mainPhaseMessages / mainThreads;
        int remainder = mainPhaseMessages % mainThreads;
        
        logger.info("Starting {} threads for remaining {} messages", mainThreads, mainPhaseMessages);
        long mainStart = System.currentTimeMillis();
        runPhase(messageQueue, mainThreads, messagesPerThread, remainder);
        long mainEnd = System.currentTimeMillis();
        logger.info("Main phase completed in {} ms", (mainEnd - mainStart));

        generatorThread.join();

        logger.info("Waiting for server responses...");
        boolean allResponses = responseLatch.await(responseWaitSeconds, TimeUnit.SECONDS);
        if (!allResponses) {
            logger.warn("Timed out waiting for responses. Remaining: {}", responseLatch.getCount());
        }

        metrics.markEnd();
        long testEndMs = System.currentTimeMillis();
        metrics.printSummary();
        try {
            metrics.writeThroughputBuckets("results/throughput_10s.csv");
            metrics.writeSummaryCsv("results/summary.csv");
        } catch (Exception e) {
            logger.warn("Failed to write metrics outputs", e);
        } finally {
            metrics.close();
        }

        // Fetch and log metrics report from server
        MetricsReportClient reportClient = new MetricsReportClient(serverUrl);
        if (reportClient.isEnabled()) {
            try {
                String reportJson = reportClient.fetchReport(testStartMs, testEndMs);
                logger.info("=== Assignment 3 Metrics Report ===\n{}", reportJson);
                logReportSummary(reportJson, testStartMs, testEndMs);
            } catch (Exception e) {
                logger.warn("Failed to fetch metrics report: {}", e.getMessage());
            }
        }

        connectionPool.closeAll();
        sharedEventLoopGroup.shutdownGracefully();
    }

    private void runPhase(BlockingQueue<MessageTemplate> queue, int numThreads, int messagesPerThread)
            throws InterruptedException {
        runPhase(queue, numThreads, messagesPerThread, 0);
    }

    private void runPhase(BlockingQueue<MessageTemplate> queue, int numThreads,
                         int messagesPerThread, int extraMessages) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            int messagesToSend = messagesPerThread + (i < extraMessages ? 1 : 0);
            Thread thread = new Thread(new SenderThread(queue, messagesToSend, connectionPool, metrics,
                    batchSize, batchMaxBytes, flushIntervalMs, flushSync, rateLimiter));
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }
    }

    /**
     * Extracts and logs a human-readable summary from the metrics report JSON.
     * Uses basic string parsing to avoid pulling in a JSON library dependency.
     */
    private void logReportSummary(String json, long startMs, long endMs) {
        try {
            String lagMs        = extractJsonString(json, "projectionLagMs");
            String consistent   = extractJsonString(json, "isConsistent");
            String activeUsers  = extractNestedValue(json, "activeUserCount");
            String topRooms     = extractTopN(json, "topRooms",  3);
            String topUsers     = extractTopN(json, "topUsers",  3);

            logger.info("=== Metrics Summary ===");
            logger.info("  window:          {}ms – {}ms  (duration {}s)",
                startMs, endMs, (endMs - startMs) / 1000);
            logger.info("  totalMessages:   {}", totalMessages);
            logger.info("  reportEndpoint:  {}", serverUrl.replaceFirst("^ws", "http")
                .replaceFirst("/chat$", "") + "/api/metrics/report");
            logger.info("  projectionLagMs: {}", lagMs);
            logger.info("  isConsistent:    {}", consistent);
            logger.info("  activeUserCount: {}", activeUsers);
            logger.info("  top3Rooms:       {}", topRooms);
            logger.info("  top3Users:       {}", topUsers);
        } catch (Exception e) {
            logger.debug("Could not parse report summary: {}", e.getMessage());
        }
    }

    /** Naive extraction of a scalar value by key from a flat JSON string. */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "N/A";
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return "N/A";
        // Skip whitespace
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        // Read until comma, closing brace, or end
        int end = start;
        boolean inStr = json.charAt(start) == '"';
        if (inStr) start++;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (inStr && c == '"') break;
            if (!inStr && (c == ',' || c == '}' || c == '\n')) break;
            end++;
        }
        return inStr ? json.substring(start, end) : json.substring(start, end).trim();
    }

    /** Extracts a value by key that may be nested anywhere in the JSON. */
    private static String extractNestedValue(String json, String key) {
        return extractJsonString(json, key);
    }

    /** Extracts the first N id values from an array field named {@code arrayKey}. */
    private static String extractTopN(String json, String arrayKey, int n) {
        String marker = "\"" + arrayKey + "\"";
        int arrIdx = json.indexOf(marker);
        if (arrIdx < 0) return "N/A";
        int open = json.indexOf('[', arrIdx);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) return "N/A";
        String arr = json.substring(open, close + 1);
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        int pos = 0;
        while (count < n && pos < arr.length()) {
            int idIdx = arr.indexOf("\"id\"", pos);
            if (idIdx < 0) break;
            int colon = arr.indexOf(':', idIdx + 4);
            if (colon < 0) break;
            int qStart = arr.indexOf('"', colon + 1);
            int qEnd   = arr.indexOf('"', qStart + 1);
            if (qStart < 0 || qEnd < 0) break;
            if (count > 0) sb.append(", ");
            sb.append(arr, qStart, qEnd + 1);
            pos = qEnd + 1;
            count++;
        }
        sb.append("]");
        return count == 0 ? "N/A" : sb.toString();
    }

    private int calculateOptimalThreads() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        return Math.max(32, availableProcessors * 4);
    }

    public static void main(String[] args) throws Exception {
        String configPath = System.getenv("CHATFLOW_CONFIG");
        String serverUrlOverride = null;
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                configPath = arg.substring("--config=".length());
            } else if (!arg.startsWith("--")) {
                serverUrlOverride = arg;
            }
        }

        if (configPath == null || configPath.isBlank()) {
            configPath = "config/client.yml";
        }

        ClientConfig config = ClientConfig.load(configPath);
        ChatClient client = new ChatClient(config.getClient(), serverUrlOverride);
        client.run();
    }
}
