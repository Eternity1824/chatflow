package com.chatflow.serverv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Netty handler for all {@code /api/*} HTTP GET endpoints.
 *
 * <p>Handler is {@link ChannelHandler.Sharable} — a single instance is shared
 * across all Netty channels.  Blocking DynamoDB / Redis calls are dispatched
 * to a dedicated {@link ExecutorService} to avoid blocking the I/O event loop.
 *
 * <h3>Routes</h3>
 * <pre>
 *   GET /api/query/rooms/{roomId}/messages?start=&end=
 *   GET /api/query/users/{userId}/messages?start=&end=
 *   GET /api/query/users/{userId}/rooms
 *   GET /api/query/active-users?start=&end=
 *   GET /api/analytics/summary?start=&end=&topN=
 *   GET /api/metrics/report?start=&end=&topN=
 * </pre>
 *
 * <p>Non-{@code /api/} requests are passed through to the next pipeline handler.
 */
@ChannelHandler.Sharable
public class ApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Default window = last 15 minutes when start/end not supplied
    static final long DEFAULT_WINDOW_MS = 15 * 60 * 1_000L;
    static final int  DEFAULT_TOP_N     = 10;

    private final QueryService           queryService;
    private final AnalyticsService       analyticsService;
    private final ProjectionHealthService healthService;
    private final ExecutorService        executor;

    private ApiHandler(QueryService q, AnalyticsService a, ProjectionHealthService h) {
        this.queryService     = q;
        this.analyticsService = a;
        this.healthService    = h;
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "api-query");
            t.setDaemon(true);
            return t;
        });
    }

    public static ApiHandler create(ServerV2PersistenceConfig config) {
        QueryService           q = new QueryService(config);
        AnalyticsService       a = new AnalyticsService(config);
        // Share the Redis sync commands between AnalyticsService and health service
        ProjectionHealthService h = new ProjectionHealthService(
            config,
            a.isEnabled() ? a.sync() : null);   // package-private accessor
        log.info("ApiHandler created: dynamo={} redis={}", q.isEnabled(), a.isEnabled());
        return new ApiHandler(q, a, h);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        if (!uri.startsWith("/api/")) {
            ctx.fireChannelRead(req.retain());
            return;
        }

        // Only GET is supported for API
        if (req.method() != HttpMethod.GET) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                errorMap("Only GET is supported for /api/*"));
            return;
        }

        // Parse path and query string
        int qm = uri.indexOf('?');
        String path = qm >= 0 ? uri.substring(0, qm) : uri;
        String qs   = qm >= 0 ? uri.substring(qm + 1) : "";

        // Dispatch to handler methods (all run on executor to avoid blocking I/O thread)
        executor.execute(() -> {
            try {
                dispatch(ctx, path, qs);
            } catch (Exception e) {
                log.error("API handler error path={}: {}", path, e.getMessage(), e);
                sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    errorMap("Internal server error: " + e.getMessage()));
            }
        });
    }

    // ── Route dispatch ────────────────────────────────────────────────────────

    private void dispatch(ChannelHandlerContext ctx, String path, String qs) {
        Map<String, String> params = parseQueryString(qs);

        // /api/query/rooms/{roomId}/messages
        if (path.startsWith("/api/query/rooms/") && path.endsWith("/messages")) {
            String roomId = path.substring("/api/query/rooms/".length(),
                path.length() - "/messages".length());
            if (roomId.isBlank()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("roomId is required")); return; }
            handleRoomMessages(ctx, roomId, params);
            return;
        }

        // /api/query/users/{userId}/messages
        if (path.startsWith("/api/query/users/") && path.endsWith("/messages")) {
            String userId = path.substring("/api/query/users/".length(),
                path.length() - "/messages".length());
            if (userId.isBlank()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("userId is required")); return; }
            handleUserMessages(ctx, userId, params);
            return;
        }

        // /api/query/users/{userId}/rooms
        if (path.startsWith("/api/query/users/") && path.endsWith("/rooms")) {
            String userId = path.substring("/api/query/users/".length(),
                path.length() - "/rooms".length());
            if (userId.isBlank()) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("userId is required")); return; }
            handleUserRooms(ctx, userId);
            return;
        }

        switch (path) {
            case "/api/query/active-users"   -> handleActiveUsers(ctx, params);
            case "/api/analytics/summary"    -> handleAnalyticsSummary(ctx, params);
            case "/api/metrics/report"       -> handleMetricsReport(ctx, params);
            default -> sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                errorMap("Unknown API endpoint: " + path));
        }
    }

    // ── Endpoint handlers ─────────────────────────────────────────────────────

    private void handleRoomMessages(ChannelHandlerContext ctx, String roomId, Map<String,String> p) {
        if (!queryService.isEnabled()) { sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, errorMap("DynamoDB not configured")); return; }
        long[] range = parseTimeRange(p);
        if (range == null) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("end must be >= start")); return; }

        List<Map<String, Object>> messages = queryService.getRoomMessages(roomId, range[0], range[1]);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("roomId", roomId);
        resp.put("start",  range[0]);
        resp.put("end",    range[1]);
        resp.put("count",  messages.size());
        resp.put("messages", messages);
        sendJson(ctx, HttpResponseStatus.OK, resp);
    }

    private void handleUserMessages(ChannelHandlerContext ctx, String userId, Map<String,String> p) {
        if (!queryService.isEnabled()) { sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, errorMap("DynamoDB not configured")); return; }
        long[] range = parseTimeRange(p);
        if (range == null) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("end must be >= start")); return; }

        List<Map<String, Object>> messages = queryService.getUserMessages(userId, range[0], range[1]);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId",   userId);
        resp.put("start",    range[0]);
        resp.put("end",      range[1]);
        resp.put("count",    messages.size());
        resp.put("messages", messages);
        sendJson(ctx, HttpResponseStatus.OK, resp);
    }

    private void handleUserRooms(ChannelHandlerContext ctx, String userId) {
        if (!queryService.isEnabled()) { sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, errorMap("DynamoDB not configured")); return; }

        List<Map<String, Object>> rooms = queryService.getUserRooms(userId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId", userId);
        resp.put("count",  rooms.size());
        resp.put("rooms",  rooms);
        sendJson(ctx, HttpResponseStatus.OK, resp);
    }

    private void handleActiveUsers(ChannelHandlerContext ctx, Map<String,String> p) {
        if (!analyticsService.isEnabled()) { sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, errorMap("Redis not configured")); return; }
        long[] range = parseTimeRange(p);
        if (range == null) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("end must be >= start")); return; }

        long count = analyticsService.getActiveUserCount(range[0], range[1]);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("start",           range[0]);
        resp.put("end",             range[1]);
        resp.put("activeUserCount", count);
        sendJson(ctx, HttpResponseStatus.OK, resp);
    }

    private void handleAnalyticsSummary(ChannelHandlerContext ctx, Map<String,String> p) {
        if (!analyticsService.isEnabled()) { sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, errorMap("Redis not configured")); return; }
        long[] range = parseTimeRange(p);
        if (range == null) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("end must be >= start")); return; }
        int topN = parseInt(p.get("topN"), DEFAULT_TOP_N);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("start",              range[0]);
        resp.put("end",                range[1]);
        resp.put("messagesPerMinute",  analyticsService.getMessagesPerMinute(range[0], range[1]));
        resp.put("messagesPerSecond",  analyticsService.getMessagesPerSecond(range[0], range[1]));
        resp.put("topUsers",           analyticsService.getTopUsers(range[0], range[1], topN));
        resp.put("topRooms",           analyticsService.getTopRooms(range[0], range[1], topN));
        resp.put("activeUserCount",    analyticsService.getActiveUserCount(range[0], range[1]));
        sendJson(ctx, HttpResponseStatus.OK, resp);
    }

    private void handleMetricsReport(ChannelHandlerContext ctx, Map<String,String> p) {
        long[] range = parseTimeRange(p);
        if (range == null) { sendJson(ctx, HttpResponseStatus.BAD_REQUEST, errorMap("end must be >= start")); return; }
        int topN = parseInt(p.get("topN"), DEFAULT_TOP_N);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt",  Instant.now().toString());
        report.put("windowStartMs", range[0]);
        report.put("windowEndMs",   range[1]);

        // coreQueries section
        Map<String, Object> core = new LinkedHashMap<>();
        if (analyticsService.isEnabled()) {
            Map<String, Object> au = new LinkedHashMap<>();
            au.put("start",           range[0]);
            au.put("end",             range[1]);
            au.put("activeUserCount", analyticsService.getActiveUserCount(range[0], range[1]));
            core.put("activeUsers", au);
        }
        report.put("coreQueries", core);

        // analytics section
        Map<String, Object> analytics = new LinkedHashMap<>();
        if (analyticsService.isEnabled()) {
            analytics.put("messagesPerMinute", analyticsService.getMessagesPerMinute(range[0], range[1]));
            analytics.put("messagesPerSecond", analyticsService.getMessagesPerSecond(range[0], range[1]));
            analytics.put("topUsers",          analyticsService.getTopUsers(range[0], range[1], topN));
            analytics.put("topRooms",          analyticsService.getTopRooms(range[0], range[1], topN));
        }
        report.put("analytics", analytics);

        // health section
        report.put("health", healthService.getHealth());

        sendJson(ctx, HttpResponseStatus.OK, report);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(body);
            FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.wrappedBuffer(json));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, json.length);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Failed to serialize JSON response", e);
            ctx.close();
        }
    }

    private static Map<String, Object> errorMap(String message) {
        return Map.of("error", message);
    }

    // ── Parameter parsing ─────────────────────────────────────────────────────

    /**
     * Parse start/end epoch-ms from query params.
     * If not provided, defaults to [now-15min, now].
     * Returns null if end < start.
     */
    static long[] parseTimeRange(Map<String, String> params) {
        long now   = System.currentTimeMillis();
        long start = parseLong(params.get("start"), now - DEFAULT_WINDOW_MS);
        long end   = parseLong(params.get("end"),   now);
        if (end < start) return null;
        return new long[]{start, end};
    }

    static Map<String, String> parseQueryString(String qs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (qs == null || qs.isBlank()) return map;
        for (String kv : qs.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                map.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
            }
        }
        return map;
    }

    static long parseLong(String s, long defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }

    static int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) return defaultVal;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }
}
