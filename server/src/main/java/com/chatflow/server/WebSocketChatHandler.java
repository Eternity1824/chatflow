package com.chatflow.server;

import com.chatflow.protocol.ChatMessage;
import com.chatflow.protocol.ServerResponse;
import com.chatflow.protocol.MessageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import io.netty.util.AttributeKey;

public class WebSocketChatHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(WebSocketChatHandler.class);
    private static final AttributeKey<Boolean> JOINED_ATTR = AttributeKey.valueOf("joined");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException("Only text frames supported");
        }

        String roomId = ctx.channel().attr(RoomIdExtractorHandler.ROOM_ID_ATTR).get();
        if (roomId == null || roomId.isBlank()) {
            roomId = "unknown";
        }
        try {
            String jsonText = ((TextWebSocketFrame) frame).text();
            // logger.info("Received (roomId={}): {}", roomId, jsonText);

            try {
                // 1. Parse JSON
                ChatMessage message = objectMapper.readValue(jsonText, ChatMessage.class);

                // 2. Validate message
                MessageValidator.ValidationResult result = MessageValidator.validate(message);

                String serverTimestamp = Instant.now().toString();

                if (!result.isValid()) {
                    // 3. Send error response for invalid message
                    ServerResponse errorResponse = ServerResponse.error(
                            result.getErrorMessage(), serverTimestamp);
                    String responseJson = objectMapper.writeValueAsString(errorResponse);
                    ctx.write(new TextWebSocketFrame(responseJson));
                    return;
                }

                // 3b. Enforce JOIN before TEXT messages
                boolean joined = Boolean.TRUE.equals(ctx.channel().attr(JOINED_ATTR).get());
                if (message.getMessageType() == ChatMessage.MessageType.JOIN) {
                    ctx.channel().attr(JOINED_ATTR).set(true);
                } else if (message.getMessageType() == ChatMessage.MessageType.LEAVE) {
                    ctx.channel().attr(JOINED_ATTR).set(false);
                } else if (message.getMessageType() == ChatMessage.MessageType.TEXT && !joined) {
                    ServerResponse errorResponse = ServerResponse.error(
                            "User must JOIN before sending TEXT", serverTimestamp);
                    String responseJson = objectMapper.writeValueAsString(errorResponse);
                    ctx.write(new TextWebSocketFrame(responseJson));
                    return;
                }

                // 4. Valid message - echo back with server timestamp
                ServerResponse successResponse = ServerResponse.success(message, serverTimestamp);
                String responseJson = objectMapper.writeValueAsString(successResponse);
                ctx.write(new TextWebSocketFrame(responseJson));

                // logger.info("Validated and echoed (roomId={}): {}", roomId, message);

            } catch (Exception e) {
                // 5. Invalid JSON format
                String serverTimestamp = Instant.now().toString();
                ServerResponse errorResponse = ServerResponse.error(
                        "Invalid JSON format: " + e.getMessage(), serverTimestamp);
                String responseJson = objectMapper.writeValueAsString(errorResponse);
                ctx.write(new TextWebSocketFrame(responseJson));
                logger.warn("Invalid JSON format", e);
            }
        } finally {
            // Intentionally empty: avoid per-message MDC overhead on hot path.
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String roomId = ctx.channel().attr(RoomIdExtractorHandler.ROOM_ID_ATTR).get();
        if (roomId == null || roomId.isBlank()) {
            roomId = "unknown";
        }
        String channelId = ctx.channel().id().asShortText();
        MDC.put("roomId", roomId);
        MDC.put("channelId", channelId);
        try {
            logger.error("WebSocket handler error", cause);
        } finally {
            MDC.remove("roomId");
            MDC.remove("channelId");
        }
        ctx.close();
    }
}
