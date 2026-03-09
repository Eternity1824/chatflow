package com.chatflow.consumer;

import java.util.ArrayList;
import java.util.List;

public class ConsumerConfig {
    private static final String DEFAULT_RABBIT_HOST = "localhost";
    private static final int DEFAULT_RABBIT_PORT = 5672;
    private static final String DEFAULT_RABBIT_USERNAME = "guest";
    private static final String DEFAULT_RABBIT_PASSWORD = "guest";
    private static final String DEFAULT_RABBIT_VHOST = "/";
    private static final String DEFAULT_EXCHANGE = "chat.exchange";

    private final String rabbitHost;
    private final int rabbitPort;
    private final String rabbitUsername;
    private final String rabbitPassword;
    private final String rabbitVirtualHost;
    private final String exchangeName;
    private final int consumerThreads;
    private final int prefetchCount;
    private final int roomStart;
    private final int roomEnd;
    private final int consumerInstanceIndex;
    private final int consumerInstanceCount;
    private final int maxRetries;
    private final long retryBackoffBaseMs;
    private final long retryBackoffMaxMs;
    private final long pollIntervalMs;
    private final long queueMessageTtlMs;
    private final int queueMaxLength;
    private final int healthPort;
    private final int metricsLogIntervalSeconds;
    private final int dedupMaxEntries;
    private final long dedupTtlMs;
    private final List<String> broadcastTargets;
    private final String internalBroadcastToken;
    private final long broadcastTimeoutMs;
    private final int roomMaxInFlight;
    private final int globalMaxInFlight;

    public ConsumerConfig(
            String rabbitHost,
            int rabbitPort,
            String rabbitUsername,
            String rabbitPassword,
            String rabbitVirtualHost,
            String exchangeName,
            int consumerThreads,
            int prefetchCount,
            int roomStart,
            int roomEnd,
            int consumerInstanceIndex,
            int consumerInstanceCount,
            int maxRetries,
            long retryBackoffBaseMs,
            long retryBackoffMaxMs,
            long pollIntervalMs,
            long queueMessageTtlMs,
            int queueMaxLength,
            int healthPort,
            int metricsLogIntervalSeconds,
            int dedupMaxEntries,
            long dedupTtlMs,
            List<String> broadcastTargets,
            String internalBroadcastToken,
            long broadcastTimeoutMs,
            int roomMaxInFlight,
            int globalMaxInFlight) {
        this.rabbitHost = rabbitHost;
        this.rabbitPort = rabbitPort;
        this.rabbitUsername = rabbitUsername;
        this.rabbitPassword = rabbitPassword;
        this.rabbitVirtualHost = rabbitVirtualHost;
        this.exchangeName = exchangeName;
        this.consumerThreads = consumerThreads;
        this.prefetchCount = prefetchCount;
        this.roomStart = roomStart;
        this.roomEnd = roomEnd;
        this.consumerInstanceCount = Math.max(1, consumerInstanceCount);
        int normalizedIndex = consumerInstanceIndex;
        if (normalizedIndex < 0) {
            normalizedIndex = 0;
        }
        if (normalizedIndex >= this.consumerInstanceCount) {
            normalizedIndex = normalizedIndex % this.consumerInstanceCount;
        }
        this.consumerInstanceIndex = normalizedIndex;
        this.maxRetries = maxRetries;
        this.retryBackoffBaseMs = retryBackoffBaseMs;
        this.retryBackoffMaxMs = retryBackoffMaxMs;
        this.pollIntervalMs = pollIntervalMs;
        this.queueMessageTtlMs = queueMessageTtlMs;
        this.queueMaxLength = queueMaxLength;
        this.healthPort = healthPort;
        this.metricsLogIntervalSeconds = metricsLogIntervalSeconds;
        this.dedupMaxEntries = dedupMaxEntries;
        this.dedupTtlMs = dedupTtlMs;
        this.broadcastTargets = broadcastTargets;
        this.internalBroadcastToken = internalBroadcastToken;
        this.broadcastTimeoutMs = broadcastTimeoutMs;
        this.roomMaxInFlight = roomMaxInFlight;
        this.globalMaxInFlight = globalMaxInFlight;
    }

    public static ConsumerConfig fromEnvironment() {
        int roomStart = intEnv("CHATFLOW_ROOM_START", 1);
        int roomEnd = intEnv("CHATFLOW_ROOM_END", 20);
        if (roomEnd < roomStart) {
            roomEnd = roomStart;
        }
        return new ConsumerConfig(
                env("CHATFLOW_RABBIT_HOST", DEFAULT_RABBIT_HOST),
                intEnv("CHATFLOW_RABBIT_PORT", DEFAULT_RABBIT_PORT),
                env("CHATFLOW_RABBIT_USERNAME", DEFAULT_RABBIT_USERNAME),
                env("CHATFLOW_RABBIT_PASSWORD", DEFAULT_RABBIT_PASSWORD),
                env("CHATFLOW_RABBIT_VHOST", DEFAULT_RABBIT_VHOST),
                env("CHATFLOW_RABBIT_EXCHANGE", DEFAULT_EXCHANGE),
                intEnv("CHATFLOW_CONSUMER_THREADS", 8),
                intEnv("CHATFLOW_CONSUMER_PREFETCH", 100),
                roomStart,
                roomEnd,
                intEnv("CHATFLOW_CONSUMER_INSTANCE_INDEX", 0),
                intEnv("CHATFLOW_CONSUMER_INSTANCE_COUNT", 1),
                intEnv("CHATFLOW_CONSUMER_MAX_RETRIES", 3),
                longEnv("CHATFLOW_CONSUMER_RETRY_BACKOFF_BASE_MS", 25L),
                longEnv("CHATFLOW_CONSUMER_RETRY_BACKOFF_MAX_MS", 2_000L),
                longEnv("CHATFLOW_CONSUMER_POLL_MS", 10L),
                longEnv("CHATFLOW_QUEUE_MESSAGE_TTL_MS", 60_000L),
                intEnv("CHATFLOW_QUEUE_MAX_LENGTH", 10_000),
                intEnv("CHATFLOW_CONSUMER_HEALTH_PORT", 8090),
                intEnv("CHATFLOW_CONSUMER_METRICS_LOG_SEC", 10),
                intEnv("CHATFLOW_CONSUMER_DEDUP_MAX_ENTRIES", 200_000),
                longEnv("CHATFLOW_CONSUMER_DEDUP_TTL_MS", 120_000L),
                parseTargets(env("CHATFLOW_BROADCAST_TARGETS", "http://localhost:8080")),
                env("CHATFLOW_INTERNAL_TOKEN", ""),
                longEnv("CHATFLOW_BROADCAST_TIMEOUT_MS", 2_000L),
                intEnv("CHATFLOW_ROOM_MAX_INFLIGHT", 8),
                intEnv("CHATFLOW_GLOBAL_MAX_INFLIGHT", 500));
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }

    private static long longEnv(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value.trim());
    }

    private static List<String> parseTargets(String raw) {
        List<String> targets = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return targets;
        }
        String[] segments = raw.split(",");
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                targets.add(trimmed);
            }
        }
        return targets;
    }

    public String queueNameForRoom(int roomId) {
        return "room." + roomId;
    }

    public String getRabbitHost() {
        return rabbitHost;
    }

    public int getRabbitPort() {
        return rabbitPort;
    }

    public String getRabbitUsername() {
        return rabbitUsername;
    }

    public String getRabbitPassword() {
        return rabbitPassword;
    }

    public String getRabbitVirtualHost() {
        return rabbitVirtualHost;
    }

    public String getExchangeName() {
        return exchangeName;
    }

    public int getConsumerThreads() {
        return consumerThreads;
    }

    public int getPrefetchCount() {
        return prefetchCount;
    }

    public int getRoomStart() {
        return roomStart;
    }

    public int getRoomEnd() {
        return roomEnd;
    }

    public int getConsumerInstanceIndex() {
        return consumerInstanceIndex;
    }

    public int getConsumerInstanceCount() {
        return consumerInstanceCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    public long getRetryBackoffMaxMs() {
        return retryBackoffMaxMs;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public long getQueueMessageTtlMs() {
        return queueMessageTtlMs;
    }

    public int getQueueMaxLength() {
        return queueMaxLength;
    }

    public int getHealthPort() {
        return healthPort;
    }

    public int getMetricsLogIntervalSeconds() {
        return metricsLogIntervalSeconds;
    }

    public int getDedupMaxEntries() {
        return dedupMaxEntries;
    }

    public long getDedupTtlMs() {
        return dedupTtlMs;
    }

    public List<String> getBroadcastTargets() {
        return broadcastTargets;
    }

    public String getInternalBroadcastToken() {
        return internalBroadcastToken;
    }

    public long getBroadcastTimeoutMs() {
        return broadcastTimeoutMs;
    }

    public int getRoomMaxInFlight() {
        return roomMaxInFlight;
    }

    public int getGlobalMaxInFlight() {
        return globalMaxInFlight;
    }
}
