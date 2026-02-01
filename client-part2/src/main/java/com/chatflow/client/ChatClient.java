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
        metrics.printSummary();
        try {
            metrics.writeThroughputBuckets("results/throughput_10s.csv");
            metrics.writeSummaryCsv("results/summary.csv");
        } catch (Exception e) {
            logger.warn("Failed to write metrics outputs", e);
        } finally {
            metrics.close();
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
