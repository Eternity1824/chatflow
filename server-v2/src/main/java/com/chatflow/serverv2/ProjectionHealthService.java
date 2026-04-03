package com.chatflow.serverv2;

import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads projection health state from Redis and computes lag metrics.
 *
 * <h3>Redis keys</h3>
 * <pre>
 *   projection:lastProjectedIngestedAt   epoch-ms string
 *   projection:lastProjectedMessageId    message UUID
 * </pre>
 *
 * <h3>projectionLagMs</h3>
 * {@code now() - lastProjectedIngestedAt}.  When no messages have been
 * projected yet, lag = -1 (unknown).
 *
 * <h3>isConsistent</h3>
 * {@code lastProjectedIngestedAt > 0 AND lag < thresholdMs}.
 */
public class ProjectionHealthService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionHealthService.class);

    private final RedisCommands<String, String> sync;
    private final boolean                       enabled;
    private final long                          thresholdMs;

    public ProjectionHealthService(ServerV2PersistenceConfig config,
                                    RedisCommands<String, String> sync) {
        this.sync        = sync;
        this.enabled     = config.isRedisEnabled() && sync != null;
        this.thresholdMs = config.projectionHealthThresholdMs;
    }

    /**
     * Returns a map with projection health fields:
     * <pre>
     *   projectionLagMs          long    (ms, or -1 if unknown)
     *   isConsistent             boolean
     *   lastProjectedIngestedAt  String  (ISO-8601, or null)
     *   lastProjectedMessageId   String  (or null)
     *   thresholdMs              long
     * </pre>
     */
    public Map<String, Object> getHealth() {
        Map<String, Object> h = new LinkedHashMap<>();
        if (!enabled) {
            h.put("projectionLagMs", -1L);
            h.put("isConsistent",    false);
            h.put("lastProjectedIngestedAt",  null);
            h.put("lastProjectedMessageId",   null);
            h.put("thresholdMs",     thresholdMs);
            return h;
        }
        try {
            String ingestedAtStr = sync.get("projection:lastProjectedIngestedAt");
            String messageId     = sync.get("projection:lastProjectedMessageId");
            long   lastIngestedAt = ingestedAtStr != null ? Long.parseLong(ingestedAtStr.trim()) : 0L;
            long   lagMs          = lastIngestedAt > 0 ? System.currentTimeMillis() - lastIngestedAt : -1L;
            boolean consistent    = lastIngestedAt > 0 && lagMs >= 0 && lagMs < thresholdMs;

            h.put("projectionLagMs",         lagMs);
            h.put("isConsistent",            consistent);
            h.put("lastProjectedIngestedAt",
                lastIngestedAt > 0 ? Instant.ofEpochMilli(lastIngestedAt).toString() : null);
            h.put("lastProjectedMessageId",  messageId);
            h.put("thresholdMs",             thresholdMs);
        } catch (Exception e) {
            log.warn("getHealth failed: {}", e.getMessage());
            h.put("projectionLagMs", -1L);
            h.put("isConsistent",    false);
            h.put("lastProjectedIngestedAt",  null);
            h.put("lastProjectedMessageId",   null);
            h.put("thresholdMs",     thresholdMs);
        }
        return h;
    }

    /**
     * Compute projectionLagMs from a given ingestedAt epoch-ms value.
     * Package-private for unit tests.
     */
    static long computeLag(long ingestedAtMs, long nowMs) {
        return ingestedAtMs > 0 ? nowMs - ingestedAtMs : -1L;
    }

    static boolean computeConsistent(long lagMs, long thresholdMs) {
        return lagMs >= 0 && lagMs < thresholdMs;
    }
}
