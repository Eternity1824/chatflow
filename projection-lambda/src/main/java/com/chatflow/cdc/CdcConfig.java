package com.chatflow.cdc;

/**
 * Loads all CDC Lambda configuration from environment variables.
 *
 * <pre>
 * DYNAMO_REGION                   AWS region (default: us-east-1)
 * DYNAMO_TABLE_ROOM_MESSAGES      projection table name (Lambda A)
 * DYNAMO_TABLE_USER_MESSAGES      projection table name (Lambda A)
 * DYNAMO_TABLE_USER_ROOMS         projection table name (Lambda A)
 * SQS_ANALYTICS_QUEUE_URL         SQS queue for analytics events (Lambda A)
 * REDIS_ENDPOINT                  host:port  (leave empty to disable Redis) (Lambda B)
 * REDIS_DEDUPE_EXPIRE_SECONDS     dedupe key TTL (default: 3600) (Lambda B)
 * </pre>
 */
public class CdcConfig {

    public final String dynamoRegion;
    public final String tableRoomMessages;
    public final String tableUserMessages;
    public final String tableUserRooms;

    /** SQS queue URL for analytics events (Lambda A sends here). Empty = SQS disabled. */
    public final String sqsAnalyticsQueueUrl;

    /** Redis hostname extracted from REDIS_ENDPOINT. Empty string = Redis disabled. */
    public final String redisHost;
    public final int    redisPort;
    public final int    dedupeExpireSeconds;

    private CdcConfig(String dynamoRegion,
                       String tableRoomMessages,
                       String tableUserMessages,
                       String tableUserRooms,
                       String sqsAnalyticsQueueUrl,
                       String redisHost,
                       int    redisPort,
                       int    dedupeExpireSeconds) {
        this.dynamoRegion          = dynamoRegion;
        this.tableRoomMessages     = tableRoomMessages;
        this.tableUserMessages     = tableUserMessages;
        this.tableUserRooms        = tableUserRooms;
        this.sqsAnalyticsQueueUrl  = sqsAnalyticsQueueUrl;
        this.redisHost             = redisHost;
        this.redisPort             = redisPort;
        this.dedupeExpireSeconds   = dedupeExpireSeconds;
    }

    /** True when SQS_ANALYTICS_QUEUE_URL is set (Lambda A should publish). */
    public boolean isSqsAnalyticsEnabled() {
        return !sqsAnalyticsQueueUrl.isBlank();
    }

    /** True when REDIS_ENDPOINT is set and Redis analytics should be applied. */
    public boolean isRedisEnabled() {
        return !redisHost.isBlank();
    }

    public static CdcConfig fromEnv() {
        String redisEndpoint = env("REDIS_ENDPOINT", "");
        String redisHost = "";
        int    redisPort = 6379;

        if (!redisEndpoint.isBlank()) {
            int colonIdx = redisEndpoint.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < redisEndpoint.length() - 1) {
                redisHost = redisEndpoint.substring(0, colonIdx);
                try {
                    redisPort = Integer.parseInt(redisEndpoint.substring(colonIdx + 1).trim());
                } catch (NumberFormatException ignored) { /* keep 6379 */ }
            } else {
                redisHost = redisEndpoint;
            }
        }

        return new CdcConfig(
            env("DYNAMO_REGION",                 "us-east-1"),
            env("DYNAMO_TABLE_ROOM_MESSAGES",    "chatflow-room-messages"),
            env("DYNAMO_TABLE_USER_MESSAGES",    "chatflow-user-messages"),
            env("DYNAMO_TABLE_USER_ROOMS",       "chatflow-user-rooms"),
            env("SQS_ANALYTICS_QUEUE_URL",       ""),
            redisHost,
            redisPort,
            intEnv("REDIS_DEDUPE_EXPIRE_SECONDS", 3600)
        );
    }

    // -- Env helpers -----------------------------------------------------------

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

    @Override
    public String toString() {
        return "CdcConfig{region=" + dynamoRegion
            + ", roomMessages=" + tableRoomMessages
            + ", userMessages=" + tableUserMessages
            + ", userRooms="    + tableUserRooms
            + ", sqsAnalytics=" + (isSqsAnalyticsEnabled() ? sqsAnalyticsQueueUrl : "disabled")
            + ", redis="        + (isRedisEnabled() ? redisHost + ":" + redisPort : "disabled")
            + "}";
    }
}
