package com.chatflow.client;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class ChatClient {
    private static final int TOTAL_MESSAGES = 500_000;
    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_MESSAGES_PER_THREAD = 1000;
    private static final int WARMUP_TOTAL = WARMUP_THREADS * WARMUP_MESSAGES_PER_THREAD;
    private static final int MAIN_PHASE_MESSAGES = TOTAL_MESSAGES - WARMUP_TOTAL;

    private final String serverUrl;
    private final MetricsCollector metrics;
    private final EventLoopGroup sharedEventLoopGroup;
    private final ConnectionPool connectionPool;

    public ChatClient(String serverUrl) {
        this.serverUrl = serverUrl;
        this.metrics = new MetricsCollector();
        this.sharedEventLoopGroup = new NioEventLoopGroup();
        this.connectionPool = new ConnectionPool(serverUrl, sharedEventLoopGroup, metrics);
    }

    public void run() throws InterruptedException {
        System.out.println("Starting ChatFlow Client");
        System.out.println("Server URL: " + serverUrl);
        System.out.println("Total messages to send: " + TOTAL_MESSAGES);

        BlockingQueue<String> messageQueue = new ArrayBlockingQueue<>(100_000);

        Thread generatorThread = new Thread(new MessageGenerator(messageQueue, TOTAL_MESSAGES));
        generatorThread.start();

        metrics.markStart();

        System.out.println("\n=== Warmup Phase ===");
        System.out.println("Starting " + WARMUP_THREADS + " threads, each sending " + 
                          WARMUP_MESSAGES_PER_THREAD + " messages");
        long warmupStart = System.currentTimeMillis();
        runPhase(messageQueue, WARMUP_THREADS, WARMUP_MESSAGES_PER_THREAD);
        long warmupEnd = System.currentTimeMillis();
        System.out.println("Warmup completed in " + (warmupEnd - warmupStart) + " ms");

        System.out.println("\n=== Main Phase ===");
        int mainThreads = calculateOptimalThreads();
        int messagesPerThread = MAIN_PHASE_MESSAGES / mainThreads;
        int remainder = MAIN_PHASE_MESSAGES % mainThreads;
        
        System.out.println("Starting " + mainThreads + " threads for remaining " + 
                          MAIN_PHASE_MESSAGES + " messages");
        long mainStart = System.currentTimeMillis();
        runPhase(messageQueue, mainThreads, messagesPerThread, remainder);
        long mainEnd = System.currentTimeMillis();
        System.out.println("Main phase completed in " + (mainEnd - mainStart) + " ms");

        generatorThread.join();

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
