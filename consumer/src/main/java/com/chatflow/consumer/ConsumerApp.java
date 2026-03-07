package com.chatflow.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ConsumerApp {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerApp.class);

    public static void main(String[] args) throws Exception {
        ConsumerConfig config = ConsumerConfig.fromEnvironment();

        logger.info("Starting consumer with threads={}, rooms={}..{}, targets={}",
                config.getConsumerThreads(),
                config.getRoomStart(),
                config.getRoomEnd(),
                config.getBroadcastTargets());
        logger.info("Retry config: maxRetries={}, backoffBaseMs={}, backoffMaxMs={}",
                config.getMaxRetries(),
                config.getRetryBackoffBaseMs(),
                config.getRetryBackoffMaxMs());
        logger.info("Dedupe config: maxEntries={}, ttlMs={}",
                config.getDedupMaxEntries(),
                config.getDedupTtlMs());

        RabbitTopologyInitializer.initialize(config);

        ConsumerMetrics metrics = new ConsumerMetrics();
        MessageDeduplicator deduplicator = new MessageDeduplicator(
                config.getDedupMaxEntries(),
                config.getDedupTtlMs());
        RoomSequenceManager roomSequenceManager = new RoomSequenceManager();
        BroadcastClient broadcastClient = new BroadcastClient(
                config.getBroadcastTargets(),
                config.getInternalBroadcastToken(),
                config.getBroadcastTimeoutMs());
        RoomManager roomManager = new RoomManager(broadcastClient, metrics);

        List<String> queues = new ArrayList<>();
        for (int roomId = config.getRoomStart(); roomId <= config.getRoomEnd(); roomId++) {
            queues.add(config.queueNameForRoom(roomId));
        }
        List<List<String>> queueAssignments = RoomAssignment.assignQueues(queues, config.getConsumerThreads());

        List<ConsumerWorker> workers = new ArrayList<>();
        ExecutorService workerPool = Executors.newFixedThreadPool(queueAssignments.size());
        for (int i = 0; i < queueAssignments.size(); i++) {
            ConsumerWorker worker = new ConsumerWorker(
                    i,
                    queueAssignments.get(i),
                    config,
                    roomManager,
                    roomSequenceManager,
                    deduplicator,
                    metrics);
            workers.add(worker);
            workerPool.submit(worker);
        }

        ConsumerHealthServer healthServer = new ConsumerHealthServer(config.getHealthPort(), metrics);
        healthServer.start();

        ScheduledExecutorService metricsLogger = Executors.newSingleThreadScheduledExecutor();
        metricsLogger.scheduleAtFixedRate(
                () -> logger.info("Consumer metrics: {}", metrics.snapshot()),
                config.getMetricsLogIntervalSeconds(),
                config.getMetricsLogIntervalSeconds(),
                TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown requested, stopping consumer...");
            for (ConsumerWorker worker : workers) {
                worker.shutdown();
            }
            workerPool.shutdownNow();
            metricsLogger.shutdownNow();
            healthServer.close();
        }));

        workerPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }
}
