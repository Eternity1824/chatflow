package com.chatflow.cdc;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Redis analytics with atomically-safe per-message deduplication.
 *
 * <h3>Atomic dedupe + analytics via Lua</h3>
 * The old two-step {@code SETNX -> recordAnalytics()} had a correctness gap:
 * if the marker write succeeded but a subsequent analytics command failed, the
 * Lambda retry would see the marker as already present and skip analytics
 * permanently (silent under-count).
 *
 * <p>Fix: {@link #dedupeAndRecordAnalytics} runs a single Lua {@code EVAL} that:
 * <ol>
 *   <li>Attempts {@code SET KEYS[1] 1 NX EX ttl} (the processed marker).</li>
 *   <li>If the key already existed, returns {@code 0} (duplicate - no-op).</li>
 *   <li>Otherwise executes all five analytics updates atomically in the same
 *       script and returns {@code 1} (first-seen).</li>
 * </ol>
 * Because all six Redis writes occur inside one Lua script, Redis executes them
 * atomically with respect to other clients - no intermediate state is visible.
 *
 * <h3>Analytics keys written atomically (on first-seen)</h3>
 * <pre>
 *   analytics:processed:{messageId}       SET NX EX ttl        (dedupe marker)
 *   active_users:minute:{min}             SADD  userId
 *   top_users:minute:{min}                ZINCRBY userId 1
 *   top_rooms:minute:{min}                ZINCRBY roomId 1
 *   messages:second:{sec}                 INCR
 *   messages:minute:{min}                 INCR
 * </pre>
 *
 * <h3>Projection health</h3>
 * {@link #updateProjectionHealth} is intentionally separate and must be called
 * only after DynamoDB projection writes AND {@link #dedupeAndRecordAnalytics}
 * both succeed.
 *
 * <h3>Redis Cluster note</h3>
 * The six keys above hash to different slots; the Lua script therefore requires
 * a single-shard / non-clustered Redis (e.g. ElastiCache single-node).
 * For Cluster support, wrap all six keys in the same hash tag, e.g.
 * {@code {msg-<id>}:active_users:...}.
 */
public class RedisAnalytics {

    private static final Logger log = LogManager.getLogger(RedisAnalytics.class);
    private static final int BATCH_ARG_WIDTH = 5;

    /**
     * Lua script: atomic batch dedupe + analytics update.
     *
     * <pre>
     * ARGV[1]  dedupe TTL in seconds
     * ARGV[2]  batch max ingestedAtMs
     * ARGV[3]  batch max messageId
     * ARGV[4]  record count
     *
     * Per record:
     *   ARGV[5 + n*5 + 0]  messageId
     *   ARGV[5 + n*5 + 1]  userId
     *   ARGV[5 + n*5 + 2]  roomId
     *   ARGV[5 + n*5 + 3]  minute bucket
     *   ARGV[5 + n*5 + 4]  second bucket
     *
     * Returns: number of first-seen records written to analytics.
     * </pre>
     */
    static final String ANALYTICS_SCRIPT =
        "local ttl = tonumber(ARGV[1])\n" +
        "local healthTs = tonumber(ARGV[2])\n" +
        "local healthId = ARGV[3]\n" +
        "local count = tonumber(ARGV[4])\n" +
        "local activeUsers = {}\n" +
        "local topUsers = {}\n" +
        "local topRooms = {}\n" +
        "local perSecond = {}\n" +
        "local perMinute = {}\n" +
        "local firstSeen = 0\n" +
        "local offset = 5\n" +
        "for i = 1, count do\n" +
        "  local messageId = ARGV[offset]\n" +
        "  local userId = ARGV[offset + 1]\n" +
        "  local roomId = ARGV[offset + 2]\n" +
        "  local minute = ARGV[offset + 3]\n" +
        "  local second = ARGV[offset + 4]\n" +
        "  offset = offset + 5\n" +
        "  if redis.call('SET', 'analytics:processed:' .. messageId, '1', 'NX', 'EX', ttl) then\n" +
        "    firstSeen = firstSeen + 1\n" +
        "    if activeUsers[minute] == nil then activeUsers[minute] = {} end\n" +
        "    activeUsers[minute][userId] = 1\n" +
        "    if topUsers[minute] == nil then topUsers[minute] = {} end\n" +
        "    topUsers[minute][userId] = (topUsers[minute][userId] or 0) + 1\n" +
        "    if topRooms[minute] == nil then topRooms[minute] = {} end\n" +
        "    topRooms[minute][roomId] = (topRooms[minute][roomId] or 0) + 1\n" +
        "    perSecond[second] = (perSecond[second] or 0) + 1\n" +
        "    perMinute[minute] = (perMinute[minute] or 0) + 1\n" +
        "  end\n" +
        "end\n" +
        "for minute, users in pairs(activeUsers) do\n" +
        "  for userId, _ in pairs(users) do\n" +
        "    redis.call('SADD', 'active_users:minute:' .. minute, userId)\n" +
        "  end\n" +
        "end\n" +
        "for minute, counts in pairs(topUsers) do\n" +
        "  for userId, delta in pairs(counts) do\n" +
        "    redis.call('ZINCRBY', 'top_users:minute:' .. minute, delta, userId)\n" +
        "  end\n" +
        "end\n" +
        "for minute, counts in pairs(topRooms) do\n" +
        "  for roomId, delta in pairs(counts) do\n" +
        "    redis.call('ZINCRBY', 'top_rooms:minute:' .. minute, delta, roomId)\n" +
        "  end\n" +
        "end\n" +
        "for second, delta in pairs(perSecond) do\n" +
        "  redis.call('INCRBY', 'messages:second:' .. second, delta)\n" +
        "end\n" +
        "for minute, delta in pairs(perMinute) do\n" +
        "  redis.call('INCRBY', 'messages:minute:' .. minute, delta)\n" +
        "end\n" +
        "local currentHealthTs = tonumber(redis.call('GET', 'projection:lastProjectedIngestedAt') or '0')\n" +
        "if healthTs > currentHealthTs then\n" +
        "  redis.call('SET', 'projection:lastProjectedIngestedAt', tostring(healthTs))\n" +
        "  redis.call('SET', 'projection:lastProjectedMessageId', healthId)\n" +
        "end\n" +
        "return firstSeen\n";

    private final boolean                                enabled;
    private final RedisClient                            client;
    private final StatefulRedisConnection<String,String> connection;
    private final RedisCommands<String, String>          sync;
    private final int                                    dedupeExpireSeconds;

    public RedisAnalytics(CdcConfig config) {
        if (config.isRedisEnabled()) {
            RedisURI uri = RedisURI.builder()
                .withHost(config.redisHost)
                .withPort(config.redisPort)
                .build();
            this.client              = RedisClient.create(uri);
            this.connection          = client.connect();
            this.sync                = connection.sync();
            this.dedupeExpireSeconds = config.dedupeExpireSeconds;
            this.enabled             = true;
            log.info("Redis connected: {}:{}", config.redisHost, config.redisPort);
        } else {
            this.enabled             = false;
            this.client              = null;
            this.connection          = null;
            this.sync                = null;
            this.dedupeExpireSeconds = 0;
            log.info("Redis disabled - analytics skipped");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Atomically deduplicate and record analytics for an invocation batch.
     *
     * <p>Each record still keeps its own processed marker, but the Redis
     * counters are aggregated in Lua and flushed once per batch to reduce write
     * amplification. Health markers are also advanced once per batch and only
     * move forward.
     *
     * @return number of first-seen records that contributed analytics updates
     */
    @SuppressWarnings("unchecked")
    public long recordBatchAnalytics(java.util.List<ProjectionEvent> events) {
        if (!enabled || events == null || events.isEmpty()) {
            return 0L;
        }
        String[] args = buildBatchAnalyticsArgs(events, dedupeExpireSeconds);
        Long result = (Long) sync.eval(ANALYTICS_SCRIPT, ScriptOutputType.INTEGER, new String[0], args);
        return result != null ? result : 0L;
    }

    /**
     * Backward-compatible single-record wrapper for tests and one-off callers.
     */
    public boolean dedupeAndRecordAnalytics(ProjectionEvent pe) {
        return recordBatchAnalytics(java.util.List.of(pe)) > 0L;
    }

    /**
     * Backward-compatible health update helper.
     */
    public void updateProjectionHealth(ProjectionEvent pe) {
        sync.set("projection:lastProjectedIngestedAt", String.valueOf(pe.ingestedAtMs));
        sync.set("projection:lastProjectedMessageId",  pe.messageId);
    }

    // Helpers for unit tests

    /**
     * Build the ordered ARGV array for {@link #ANALYTICS_SCRIPT}.
     */
    static String[] buildBatchAnalyticsArgs(java.util.List<ProjectionEvent> events, int dedupeExpireSeconds) {
        ProjectionEvent latest = latestByIngestedAt(events);
        java.util.List<String> args = new java.util.ArrayList<>(4 + events.size() * BATCH_ARG_WIDTH);
        args.add(String.valueOf(dedupeExpireSeconds));
        args.add(String.valueOf(latest != null ? latest.ingestedAtMs : 0L));
        args.add(latest != null ? latest.messageId : "");
        args.add(String.valueOf(events.size()));
        for (ProjectionEvent pe : events) {
            args.add(pe.messageId);
            args.add(pe.userId);
            args.add(pe.roomId);
            args.add(String.valueOf(pe.minuteBucket()));
            args.add(String.valueOf(pe.secondBucket()));
        }
        return args.toArray(new String[0]);
    }

    static ProjectionEvent latestByIngestedAt(java.util.List<ProjectionEvent> events) {
        ProjectionEvent latest = null;
        for (ProjectionEvent pe : events) {
            if (latest == null || pe.ingestedAtMs > latest.ingestedAtMs) {
                latest = pe;
            }
        }
        return latest;
    }
}
