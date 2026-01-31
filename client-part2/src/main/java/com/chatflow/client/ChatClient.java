package com.chatflow.client;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
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
    private final ConnectionPool connectionPool;
    private final CountDownLatch responseLatch;
    private final int totalMessages;
    private final int warmupThreads;
    private final int warmupMessagesPerThread;
    private final int warmupTotal;
    private final int mainPhaseMessages;
    private final int responseWaitSeconds;

    public ChatClient(ClientConfig.ClientSettings settings, String serverUrlOverride) {
        this.serverUrl = serverUrlOverride != null ? serverUrlOverride : settings.getServerUrl();
        this.metrics = new DetailedMetricsCollector();
        this.totalMessages = settings.getTotalMessages();
        this.warmupThreads = settings.getWarmupThreads();
        this.warmupMessagesPerThread = settings.getWarmupMessagesPerThread();
        this.warmupTotal = warmupThreads * warmupMessagesPerThread;
        this.mainPhaseMessages = totalMessages - warmupTotal;
        this.responseWaitSeconds = settings.getResponseWaitSeconds();
        this.sharedEventLoopGroup = new NioEventLoopGroup();
        this.responseLatch = new CountDownLatch(totalMessages);
        this.connectionPool = new ConnectionPool(serverUrl, sharedEventLoopGroup, metrics,
                responseLatch, settings.getConnectionsPerRoom());
    }

    public void run() throws InterruptedException {
        logger.info("Starting ChatFlow Client");
        logger.info("Server URL: {}", serverUrl);
        logger.info("Total messages to send: {}", totalMessages);

        BlockingQueue<String> messageQueue = new ArrayBlockingQueue<>(100_000);

        Thread generatorThread = new Thread(new MessageGenerator(messageQueue, totalMessages));
        generatorThread.start();

        metrics.markStart();

        logger.info("=== Warmup Phase ===");
        logger.info("Starting {} threads, each sending {} messages", warmupThreads, warmupMessagesPerThread);
        long warmupStart = System.currentTimeMillis();
        runPhase(messageQueue, warmupThreads, warmupMessagesPerThread);
        long warmupEnd = System.currentTimeMillis();
        logger.info("Warmup completed in {} ms", (warmupEnd - warmupStart));

        logger.info("=== Main Phase ===");
        int mainThreads = calculateOptimalThreads();
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
        metrics.exportToCsv("metrics_output.csv");
        
        connectionPool.closeAll();
        sharedEventLoopGroup.shutdownGracefully();
    }

    private void runPhase(BlockingQueue<String> queue, int numThreads, int messagesPerThread) 
            throws InterruptedException {
        runPhase(queue, numThreads, messagesPerThread, 0);
    }

    private void runPhase(BlockingQueue<String> queue, int numThreads, 
                         int messagesPerThread, int extraMessages) throws InterruptedException {
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            int messagesToSend = messagesPerThread + (i < extraMessages ? 1 : 0);
            Thread thread = new Thread(new SenderThread(queue, messagesToSend, connectionPool));
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
