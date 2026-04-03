package com.chatflow.serverv2;

/**
 * Loads persistence/analytics config from env vars (same prefix as consumer-v3
 * so one env file covers both services on the same host).
 *
 * All fields are optional — if tables/Redis are not set, the API returns 503.
 */
public class ServerV2PersistenceConfig {

    public final String dynamoRegion;
    public final String tableRoomMessages;
    public final String tableUserMessages;
    public final String tableUserRooms;

    /** Redis host. Empty = Redis disabled. */
    public final String redisHost;
    public final int    redisPort;

    /** Projection lag threshold (ms) for isConsistent health field. Default 5 000. */
    public final long projectionHealthThresholdMs;

    private ServerV2PersistenceConfig(
            String dynamoRegion,
            String tableRoomMessages,
            String tableUserMessages,
            String tableUserRooms,
            String redisHost,
            int    redisPort,
            long   projectionHealthThresholdMs) {
        this.dynamoRegion                = dynamoRegion;
        this.tableRoomMessages           = tableRoomMessages;
        this.tableUserMessages           = tableUserMessages;
        this.tableUserRooms              = tableUserRooms;
        this.redisHost                   = redisHost;
        this.redisPort                   = redisPort;
        this.projectionHealthThresholdMs = projectionHealthThresholdMs;
    }

    public boolean isDynamoEnabled() {
        return tableRoomMessages != null && !tableRoomMessages.isBlank();
    }

    public boolean isRedisEnabled() {
        return redisHost != null && !redisHost.isBlank();
    }

    public static ServerV2PersistenceConfig fromEnv() {
        String redisEndpoint = env("CHATFLOW_V3_REDIS_ENDPOINT", "");
        String redisHost = "";
        int    redisPort = 6379;
        if (!redisEndpoint.isBlank()) {
            int colon = redisEndpoint.lastIndexOf(':');
            if (colon > 0 && colon < redisEndpoint.length() - 1) {
                redisHost = redisEndpoint.substring(0, colon);
                try { redisPort = Integer.parseInt(redisEndpoint.substring(colon + 1).trim()); }
                catch (NumberFormatException ignored) { /* keep 6379 */ }
            } else {
                redisHost = redisEndpoint;
            }
        }
        return new ServerV2PersistenceConfig(
            env("CHATFLOW_V3_DYNAMO_REGION",               "us-east-1"),
            env("CHATFLOW_V3_DYNAMO_TABLE_ROOM_MESSAGES",  ""),
            env("CHATFLOW_V3_DYNAMO_TABLE_USER_MESSAGES",  ""),
            env("CHATFLOW_V3_DYNAMO_TABLE_USER_ROOMS",     ""),
            redisHost,
            redisPort,
            longEnv("CHATFLOW_V3_PROJECTION_HEALTH_THRESHOLD_MS", 5_000L)
        );
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }

    private static long longEnv(String key, long def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    @Override
    public String toString() {
        return "ServerV2PersistenceConfig{dynamo=" + isDynamoEnabled()
            + ", redis=" + isRedisEnabled()
            + (isRedisEnabled() ? "(" + redisHost + ":" + redisPort + ")" : "")
            + ", healthThreshold=" + projectionHealthThresholdMs + "ms}";
    }
}
