package com.chatflow.serverv2;

public class RabbitMqConfig {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5672;
    private static final String DEFAULT_USERNAME = "guest";
    private static final String DEFAULT_PASSWORD = "guest";
    private static final String DEFAULT_EXCHANGE = "chat.exchange";
    private static final String DEFAULT_VIRTUAL_HOST = "/";

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final String exchangeName;
    private final int channelPoolSize;
    private final int roomStart;
    private final int roomEnd;
    private final long queueMessageTtlMs;
    private final int queueMaxLength;
    private final boolean circuitBreakerEnabled;
    private final int circuitBreakerFailureThreshold;
    private final long circuitBreakerOpenDurationMs;

    public RabbitMqConfig(
            String host,
            int port,
            String username,
            String password,
            String virtualHost,
            String exchangeName,
            int channelPoolSize,
            int roomStart,
            int roomEnd,
            long queueMessageTtlMs,
            int queueMaxLength,
            boolean circuitBreakerEnabled,
            int circuitBreakerFailureThreshold,
            long circuitBreakerOpenDurationMs) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.exchangeName = exchangeName;
        this.channelPoolSize = channelPoolSize;
        this.roomStart = roomStart;
        this.roomEnd = roomEnd;
        this.queueMessageTtlMs = queueMessageTtlMs;
        this.queueMaxLength = queueMaxLength;
        this.circuitBreakerEnabled = circuitBreakerEnabled;
        this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
        this.circuitBreakerOpenDurationMs = circuitBreakerOpenDurationMs;
    }

    public static RabbitMqConfig fromEnvironment() {
        int roomStart = Integer.parseInt(env("CHATFLOW_ROOM_START", "1"));
        int roomEnd = Integer.parseInt(env("CHATFLOW_ROOM_END", "20"));
        if (roomEnd < roomStart) {
            roomEnd = roomStart;
        }
        return new RabbitMqConfig(
                env("CHATFLOW_RABBIT_HOST", DEFAULT_HOST),
                Integer.parseInt(env("CHATFLOW_RABBIT_PORT", String.valueOf(DEFAULT_PORT))),
                env("CHATFLOW_RABBIT_USERNAME", DEFAULT_USERNAME),
                env("CHATFLOW_RABBIT_PASSWORD", DEFAULT_PASSWORD),
                env("CHATFLOW_RABBIT_VHOST", DEFAULT_VIRTUAL_HOST),
                env("CHATFLOW_RABBIT_EXCHANGE", DEFAULT_EXCHANGE),
                Integer.parseInt(env("CHATFLOW_RABBIT_CHANNEL_POOL_SIZE", "8")),
                roomStart,
                roomEnd,
                Long.parseLong(env("CHATFLOW_QUEUE_MESSAGE_TTL_MS", "60000")),
                Integer.parseInt(env("CHATFLOW_QUEUE_MAX_LENGTH", "10000")),
                Boolean.parseBoolean(env("CHATFLOW_CIRCUIT_BREAKER_ENABLED", "true")),
                Integer.parseInt(env("CHATFLOW_CIRCUIT_BREAKER_FAILURE_THRESHOLD", "5")),
                Long.parseLong(env("CHATFLOW_CIRCUIT_BREAKER_OPEN_MS", "5000")));
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public String getExchangeName() {
        return exchangeName;
    }

    public int getChannelPoolSize() {
        return channelPoolSize;
    }

    public int getRoomStart() {
        return roomStart;
    }

    public int getRoomEnd() {
        return roomEnd;
    }

    public long getQueueMessageTtlMs() {
        return queueMessageTtlMs;
    }

    public int getQueueMaxLength() {
        return queueMaxLength;
    }

    public boolean isCircuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public long getCircuitBreakerOpenDurationMs() {
        return circuitBreakerOpenDurationMs;
    }
}
