package com.chatflow.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class RoomIdExtractorHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    public static final AttributeKey<String> ROOM_ID_ATTR = AttributeKey.valueOf("roomId");
    private static final Logger logger = LoggerFactory.getLogger(RoomIdExtractorHandler.class);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        String channelId = ctx.channel().id().asShortText();
        MDC.put("channelId", channelId);
        try {
            if (isHealthCheck(uri)) {
                sendResponse(ctx, HttpResponseStatus.OK, "OK");
                return;
            }
            String roomId = extractRoomId(uri);
            if (roomId != null && !roomId.isBlank()) {
                MDC.put("roomId", roomId);
                ctx.channel().attr(ROOM_ID_ATTR).set(roomId);
            }

            if (isChatPath(uri) && (roomId == null || roomId.isBlank())) {
                logger.warn("Missing roomId for request uri={}", uri);
                sendResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Missing roomId");
                return;
            }

            ctx.fireChannelRead(req.retain());
        } finally {
            MDC.remove("roomId");
            MDC.remove("channelId");
        }
    }

    private boolean isHealthCheck(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals("/health") || uri.startsWith("/health?");
    }

    private boolean isChatPath(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.equals("/chat") || uri.equals("/chat/") || uri.startsWith("/chat?");
    }

    private String extractRoomId(String uri) {
        if (uri == null) {
            return null;
        }
        int questionMark = uri.indexOf('?');
        String path = questionMark >= 0 ? uri.substring(0, questionMark) : uri;

        if (path.startsWith("/chat/")) {
            String roomId = path.substring("/chat/".length());
            return roomId.isBlank() ? null : roomId;
        }

        if (path.equals("/chat") || path.equals("/chat/")) {
            return extractRoomIdFromQuery(questionMark >= 0 ? uri.substring(questionMark + 1) : "");
        }

        return null;
    }

    private String extractRoomIdFromQuery(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return null;
        }
        String[] params = queryString.split("&");
        for (String param : params) {
            String[] keyValue = param.split("=", 2);
            if (keyValue.length == 2 && "roomId".equals(keyValue[0])) {
                String roomId = keyValue[1];
                return roomId.isBlank() ? null : roomId;
            }
        }
        return null;
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(future -> ctx.close());
    }
}
