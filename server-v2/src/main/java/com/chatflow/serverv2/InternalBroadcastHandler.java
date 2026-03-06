package com.chatflow.serverv2;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public class InternalBroadcastHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(InternalBroadcastHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BROADCAST_PATH = "/internal/broadcast";
    private static final String INTERNAL_TOKEN_HEADER = "X-Chatflow-Token";

    private final RoomSessionRegistry roomSessionRegistry;
    private final String internalToken;
    private final RecentMessageTracker messageTracker;

    public InternalBroadcastHandler(RoomSessionRegistry roomSessionRegistry, String internalToken) {
        this.roomSessionRegistry = roomSessionRegistry;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.messageTracker = new RecentMessageTracker(200_000, 120_000L);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.uri();
        String path = extractPath(uri);
        if (!BROADCAST_PATH.equals(path)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        if (!HttpMethod.POST.equals(request.method())) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, map("error", "POST required"));
            return;
        }
        if (!isTokenValid(request)) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, map("error", "Unauthorized"));
            return;
        }

        QueueChatMessage queueMessage;
        try {
            byte[] payload = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), payload);
            queueMessage = OBJECT_MAPPER.readValue(new ByteArrayInputStream(payload), QueueChatMessage.class);
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, map("error", "Invalid JSON payload"));
            return;
        }

        if (queueMessage.getRoomId() == null || queueMessage.getRoomId().isBlank()) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, map("error", "roomId is required"));
            return;
        }

        if (messageTracker.isDuplicate(queueMessage.getMessageId())) {
            sendJson(ctx, HttpResponseStatus.OK, map(
                    "status", "duplicate",
                    "messageId", queueMessage.getMessageId(),
                    "deliveredCount", 0));
            return;
        }

        int deliveredCount = roomSessionRegistry.broadcast(queueMessage);
        sendJson(ctx, HttpResponseStatus.OK, map(
                "status", "ok",
                "messageId", queueMessage.getMessageId(),
                "roomId", queueMessage.getRoomId(),
                "deliveredCount", deliveredCount));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("Internal broadcast handler error", cause);
        sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, map("error", "Internal server error"));
    }

    private boolean isTokenValid(FullHttpRequest request) {
        if (internalToken.isBlank()) {
            return true;
        }
        String requestToken = request.headers().get(INTERNAL_TOKEN_HEADER);
        return internalToken.equals(requestToken);
    }

    private String extractPath(String uri) {
        if (uri == null) {
            return "";
        }
        int queryIndex = uri.indexOf('?');
        return queryIndex >= 0 ? uri.substring(0, queryIndex) : uri;
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            Object value = pairs[i + 1];
            result.put(String.valueOf(key), value);
        }
        return result;
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Map<String, Object> payload) {
        try {
            String body = OBJECT_MAPPER.writeValueAsString(payload);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    status,
                    Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(future -> ctx.close());
        } catch (Exception e) {
            logger.warn("Failed to send JSON response", e);
            ctx.close();
        }
    }
}
