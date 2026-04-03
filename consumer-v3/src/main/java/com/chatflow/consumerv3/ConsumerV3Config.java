package com.chatflow.consumerv3;

/**
 * Loads all configuration from environment variables.
 * All env-var names use the {@code CHATFLOW_V3_*} prefix to avoid
 * collision with the existing {@code consumer} module's vars.
 */
public class ConsumerV3Config {

    // ── RabbitMQ ──────────────────────────────────────────────────────────────
    public final String rabbitHost;
    public final int    rabbitPort;
    public final String rabbitUsername;
    public final String rabbitPassword;
    public final String rabbitVhost;
    public final String rabbitExchange;
    public final int    rabbitPrefetch;

    // ── Room / queue sharding ─────────────────────────────────────────────────
    public final int  roomStart;
    public final int  roomEnd;
    public final long queueMessageTtlMs;
    public final int  queueMaxLength;

    // ── DynamoDB ──────────────────────────────────────────────────────────────
    public final String dynamoRegion;
    public final String dynamoTableCanonical;   // messages_by_id

    // ── SQS DLQ ───────────────────────────────────────────────────────────────
    public final String sqsDlqUrl;

    // ── Batch / flush ─────────────────────────────────────────────────────────
    public final int  batchSize;
    public final long flushIntervalMs;
    public final int  semaphorePermits;

    // ── Retry  (env: CHATFLOW_V3_MAX_RETRIES etc.) ────────────────────────────
    public final long retryBaseMs;
    public final long retryMaxMs;
    public final int  maxRetries;

    // ── Circuit breaker ───────────────────────────────────────────────────────
    /** Enable/disable the circuit breaker. */
    public final boolean cbEnabled;
    /**
     * Number of consecutive failing batches (each with ≥1 terminal failure)
     * before the circuit opens.
     */
    public final int     cbFailureThreshold;
    /** How long (ms) the circuit stays OPEN before auto-resetting to CLOSED. */
    public final long    cbOpenDurationMs;

    // ── Observability ─────────────────────────────────────────────────────────
    public final int  metricsPort;
    public final long metricsLogIntervalMs;

    private ConsumerV3Config(Builder b) {
        this.rabbitHost           = b.rabbitHost;
        this.rabbitPort           = b.rabbitPort;
        this.rabbitUsername       = b.rabbitUsername;
        this.rabbitPassword       = b.rabbitPassword;
        this.rabbitVhost          = b.rabbitVhost;
        this.rabbitExchange       = b.rabbitExchange;
        this.rabbitPrefetch       = b.rabbitPrefetch;
        this.roomStart            = b.roomStart;
        this.roomEnd              = b.roomEnd;
        this.queueMessageTtlMs    = b.queueMessageTtlMs;
        this.queueMaxLength       = b.queueMaxLength;
        this.dynamoRegion         = b.dynamoRegion;
        this.dynamoTableCanonical = b.dynamoTableCanonical;
        this.sqsDlqUrl            = b.sqsDlqUrl;
        this.batchSize            = b.batchSize;
        this.flushIntervalMs      = b.flushIntervalMs;
        this.semaphorePermits     = b.semaphorePermits;
        this.retryBaseMs          = b.retryBaseMs;
        this.retryMaxMs           = b.retryMaxMs;
        this.maxRetries           = b.maxRetries;
        this.cbEnabled            = b.cbEnabled;
        this.cbFailureThreshold   = b.cbFailureThreshold;
        this.cbOpenDurationMs     = b.cbOpenDurationMs;
        this.metricsPort          = b.metricsPort;
        this.metricsLogIntervalMs = b.metricsLogIntervalMs;
    }

    /** Queue name for a given room ID — matches the existing consumer convention. */
    public String queueNameForRoom(int roomId) {
        return "room." + roomId;
    }

    /** Load all configuration from process environment variables. */
    public static ConsumerV3Config fromEnv() {
        int roomStart = intEnv("CHATFLOW_V3_ROOM_START", 1);
        int roomEnd   = intEnv("CHATFLOW_V3_ROOM_END",  20);
        if (roomEnd < roomStart) roomEnd = roomStart;

        return new Builder()
            // RabbitMQ
            .rabbitHost(env("CHATFLOW_V3_RABBIT_HOST", "localhost"))
            .rabbitPort(intEnv("CHATFLOW_V3_RABBIT_PORT", 5672))
            .rabbitUsername(env("CHATFLOW_V3_RABBIT_USERNAME", "guest"))
            .rabbitPassword(env("CHATFLOW_V3_RABBIT_PASSWORD", "guest"))
            .rabbitVhost(env("CHATFLOW_V3_RABBIT_VHOST", "/"))
            .rabbitExchange(env("CHATFLOW_V3_RABBIT_EXCHANGE", "chat.exchange"))
            .rabbitPrefetch(intEnv("CHATFLOW_V3_RABBIT_PREFETCH", 200))
            // Rooms
            .roomStart(roomStart)
            .roomEnd(roomEnd)
            .queueMessageTtlMs(longEnv("CHATFLOW_V3_QUEUE_TTL_MS", 60_000L))
            .queueMaxLength(intEnv("CHATFLOW_V3_QUEUE_MAX_LENGTH", 10_000))
            // DynamoDB
            .dynamoRegion(env("CHATFLOW_V3_DYNAMO_REGION", "us-east-1"))
            .dynamoTableCanonical(env("CHATFLOW_V3_DYNAMO_TABLE_CANONICAL", "messages_by_id"))
            // SQS DLQ
            .sqsDlqUrl(env("CHATFLOW_V3_SQS_DLQ_URL", ""))
            // Batch
            .batchSize(intEnv("CHATFLOW_V3_BATCH_SIZE", 100))
            .flushIntervalMs(longEnv("CHATFLOW_V3_FLUSH_INTERVAL_MS", 500))
            .semaphorePermits(intEnv("CHATFLOW_V3_SEMAPHORE_PERMITS", 64))
            // Retry
            .retryBaseMs(longEnv("CHATFLOW_V3_RETRY_BASE_MS", 50))
            .retryMaxMs(longEnv("CHATFLOW_V3_RETRY_MAX_MS", 5_000))
            .maxRetries(intEnv("CHATFLOW_V3_MAX_RETRIES", 5))
            // Circuit breaker
            .cbEnabled(boolEnv("CHATFLOW_V3_CB_ENABLED", true))
            .cbFailureThreshold(intEnv("CHATFLOW_V3_CB_FAILURE_THRESHOLD", 5))
            .cbOpenDurationMs(longEnv("CHATFLOW_V3_CB_OPEN_MS", 30_000))
            // Observability
            .metricsPort(intEnv("CHATFLOW_V3_METRICS_PORT", 8091))
            .metricsLogIntervalMs(longEnv("CHATFLOW_V3_METRICS_LOG_INTERVAL_MS", 30_000))
            .build();
    }

    // ── Private env-var helpers ───────────────────────────────────────────────

    private static String env(String key, String defaultVal) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultVal;
    }

    private static int intEnv(String key, int defaultVal) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static long longEnv(String key, long defaultVal) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private static boolean boolEnv(String key, boolean defaultVal) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultVal;
        return Boolean.parseBoolean(v.trim());
    }

    @Override
    public String toString() {
        return "ConsumerV3Config{rabbit=" + rabbitHost + ":" + rabbitPort
            + ", rooms=" + roomStart + ".." + roomEnd
            + ", dynamo=" + dynamoRegion + "/" + dynamoTableCanonical
            + ", dlqUrl=" + (sqsDlqUrl.isBlank() ? "<none>" : "<set>")
            + ", batch=" + batchSize + ", flush=" + flushIntervalMs + "ms"
            + ", maxRetries=" + maxRetries
            + ", cb=" + cbEnabled + "(thr=" + cbFailureThreshold
            + ",open=" + cbOpenDurationMs + "ms)" + "}";
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        String  rabbitHost = "localhost";  int    rabbitPort = 5672;
        String  rabbitUsername = "guest";  String rabbitPassword = "guest";
        String  rabbitVhost = "/";         String rabbitExchange = "chat.exchange";
        int     rabbitPrefetch = 200;
        int     roomStart = 1;             int    roomEnd = 20;
        long    queueMessageTtlMs = 60_000; int   queueMaxLength = 10_000;
        String  dynamoRegion = "us-east-1"; String dynamoTableCanonical = "messages_by_id";
        String  sqsDlqUrl = "";
        int     batchSize = 100;           long   flushIntervalMs = 500;
        int     semaphorePermits = 64;
        long    retryBaseMs = 50;          long   retryMaxMs = 5_000;
        int     maxRetries = 5;
        boolean cbEnabled = true;          int    cbFailureThreshold = 5;
        long    cbOpenDurationMs = 30_000;
        int     metricsPort = 8091;        long   metricsLogIntervalMs = 30_000;

        public Builder rabbitHost(String v)            { this.rabbitHost = v; return this; }
        public Builder rabbitPort(int v)               { this.rabbitPort = v; return this; }
        public Builder rabbitUsername(String v)        { this.rabbitUsername = v; return this; }
        public Builder rabbitPassword(String v)        { this.rabbitPassword = v; return this; }
        public Builder rabbitVhost(String v)           { this.rabbitVhost = v; return this; }
        public Builder rabbitExchange(String v)        { this.rabbitExchange = v; return this; }
        public Builder rabbitPrefetch(int v)           { this.rabbitPrefetch = v; return this; }
        public Builder roomStart(int v)                { this.roomStart = v; return this; }
        public Builder roomEnd(int v)                  { this.roomEnd = v; return this; }
        public Builder queueMessageTtlMs(long v)       { this.queueMessageTtlMs = v; return this; }
        public Builder queueMaxLength(int v)           { this.queueMaxLength = v; return this; }
        public Builder dynamoRegion(String v)          { this.dynamoRegion = v; return this; }
        public Builder dynamoTableCanonical(String v)  { this.dynamoTableCanonical = v; return this; }
        public Builder sqsDlqUrl(String v)             { this.sqsDlqUrl = v; return this; }
        public Builder batchSize(int v)                { this.batchSize = v; return this; }
        public Builder flushIntervalMs(long v)         { this.flushIntervalMs = v; return this; }
        public Builder semaphorePermits(int v)         { this.semaphorePermits = v; return this; }
        public Builder retryBaseMs(long v)             { this.retryBaseMs = v; return this; }
        public Builder retryMaxMs(long v)              { this.retryMaxMs = v; return this; }
        public Builder maxRetries(int v)               { this.maxRetries = v; return this; }
        public Builder cbEnabled(boolean v)            { this.cbEnabled = v; return this; }
        public Builder cbFailureThreshold(int v)       { this.cbFailureThreshold = v; return this; }
        public Builder cbOpenDurationMs(long v)        { this.cbOpenDurationMs = v; return this; }
        public Builder metricsPort(int v)              { this.metricsPort = v; return this; }
        public Builder metricsLogIntervalMs(long v)    { this.metricsLogIntervalMs = v; return this; }
        public ConsumerV3Config build()                { return new ConsumerV3Config(this); }
    }
}
