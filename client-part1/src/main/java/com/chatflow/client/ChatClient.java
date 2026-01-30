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
    private static final int TOTAL_MESSAGES = 500_000;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    private static final int WARMUP_TOTAL = WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD;
    private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

    private final String serverUrl;
    private final MetricsCollector metrics;
    private final EventLoopGroup sharedEventLoopGroup;
    private final ConnectionPool connectionPool;
    private final CountDownLatch responseLatch;

    public ChatClient(String serverUrl) {
        this.serverUrl = serverUrl;
        this.metrics = new MetricsCollector();
        this.sharedEventLoopGroup = new NioEventLoopGroup();
        this.responseLatch = new CountDownLatch(TOTAL_MESSAGES);
        this.connectionPool = new ConnectionPool(serverUrl, sharedEventLoopGroup, metrics, responseLatch);
    }

    public void run() throws InterruptedException {
        logger.info("Starting ChatFlow Client");
        logger.info("Server URL: {}", serverUrl);
        logger.info("Total messages to send: {}", TOTAL_MESSAGES);

        BlockingQueue<String> messageQueue = new ArrayBlockingQueue<>(100_000);

        Thread generatorThread = new Thread(new MessageGenerator(messageQueue, TOTAL_MESSAGES));
        generatorThread.start();

        metrics.markStart();

        logger.info("=== Warmup Phase ===");
        logger.info("Starting {} threads, each sending {} messages", WARMUP_THREADS, WARMUP_MESSAGES_PER_THREAD);
        long warmupStart = System.currentTimeMillis();
        runPhase(messageQueue, WARMUP_THREADS, WARMUP_MESSAGES_PER_THREAD);
        long warmupEnd = System.currentTimeMillis();
        logger.info("Warmup completed in {} ms", (warmupEnd - warmupStart));

        logger.info("=== Main Phase ===");
        int mainThreads = calculateOptimalThreads();
        int messagesPerThread = MAIN_PHASE_MESSAGES / mainThreads;
        int remainder = MAIN_PHASE_MESSAGES % mainThreads;
        
        logger.info("Starting {} threads for remaining {} messages", mainThreads, MAIN_PHASE_MESSAGES);
        long mainStart = System.currentTimeMillis();
        runPhase(messageQueue, mainThreads, messagesPerThread, remainder);
        long mainEnd = System.currentTimeMillis();
        logger.info("Main phase completed in {} ms", (mainEnd - mainStart));

        generatorThread.join();

        logger.info("Waiting for server responses...");
        boolean allResponses = responseLatch.await(30, TimeUnit.SECONDS);
        if (!allResponses) {
            logger.warn("Timed out waiting for responses. Remaining: {}", responseLatch.getCount());
        }

        metrics.markEnd();
        metrics.printSummary();
        
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

    public static void main(String[] args) throws InterruptedException {
        String serverUrl = "ws://localhost:8080/chat";
        if (args.length > 0) {
            serverUrl = args[0];
        }

        ChatClient client = new ChatClient(serverUrl);
        client.run();
    }
}
