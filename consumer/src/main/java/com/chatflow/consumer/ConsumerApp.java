package com.chatflow.consumer;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
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
        logger.info(
                "Shard config: instanceIndex={}, instanceCount={}, shardRule=(roomId-1) % instanceCount",
                config.getConsumerInstanceIndex(),
                config.getConsumerInstanceCount());
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
        GrpcBroadcastClient grpcClient = new GrpcBroadcastClient(
                config.getBroadcastTargets(),
                config.getBroadcastTimeoutMs());

        List<String> queues = new ArrayList<>();
        for (int roomId = config.getRoomStart(); roomId <= config.getRoomEnd(); roomId++) {
            if (Math.floorMod(roomId - 1, config.getConsumerInstanceCount()) != config.getConsumerInstanceIndex()) {
                continue;
            }
            queues.add(config.queueNameForRoom(roomId));
        }
        logger.info("Assigned room queues for this consumer instance: {}", queues);
        List<List<String>> queueAssignments = RoomAssignment.assignQueues(queues, config.getConsumerThreads());

        boolean useEpoll = Epoll.isAvailable();
        EventLoopGroup eventLoopGroup = createEventLoopGroup(useEpoll, config.getConsumerThreads());
        logger.info("Using {} EventLoopGroup with {} threads (Protobuf+gRPC mode)", useEpoll ? "Epoll" : "NIO", config.getConsumerThreads());

        List<ProtobufConsumerWorker> workers = new ArrayList<>();
        for (int i = 0; i < queueAssignments.size(); i++) {
            ProtobufConsumerWorker worker = new ProtobufConsumerWorker(
                    i,
                    queueAssignments.get(i),
                    config,
                    grpcClient,
                    roomSequenceManager,
                    deduplicator,
                    metrics,
                    eventLoopGroup.next());
            workers.add(worker);
            worker.start();
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
            for (ProtobufConsumerWorker worker : workers) {
                worker.shutdown();
            }
            grpcClient.close();
            eventLoopGroup.shutdownGracefully();
            metricsLogger.shutdownNow();
            healthServer.close();
        }));

        eventLoopGroup.terminationFuture().sync();
    }

    private static EventLoopGroup createEventLoopGroup(boolean useEpoll, int threads) {
        if (useEpoll) {
            return threads > 0 ? new EpollEventLoopGroup(threads) : new EpollEventLoopGroup();
        }
        return threads > 0 ? new NioEventLoopGroup(threads) : new NioEventLoopGroup();
    }
}
