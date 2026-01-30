package com.chatflow.server;

import com.chatflow.protocol.ChatMessage;
import com.chatflow.protocol.ServerResponse;
import com.chatflow.protocol.MessageValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.time.Instant;

public class WebSocketChatHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException("Only text frames supported");
        }

        String roomId = ctx.channel().attr(RoomIdExtractorHandler.ROOM_ID_ATTR).get();
        if (roomId == null || roomId.isBlank()) {
            roomId = "unknown";
        }

        String jsonText = ((TextWebSocketFrame) frame).text();
        System.out.println("Received (roomId=" + roomId + "): " + jsonText);

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
                ctx.writeAndFlush(new TextWebSocketFrame(responseJson));
                return;
            }

            // 4. Valid message - echo back with server timestamp
            ServerResponse successResponse = ServerResponse.success(message, serverTimestamp);
            String responseJson = objectMapper.writeValueAsString(successResponse);
            ctx.writeAndFlush(new TextWebSocketFrame(responseJson));

            System.out.println("Validated and echoed (roomId=" + roomId + "): " + message);

        } catch (Exception e) {
            // 5. Invalid JSON format
            String serverTimestamp = Instant.now().toString();
            ServerResponse errorResponse = ServerResponse.error(
                    "Invalid JSON format: " + e.getMessage(), serverTimestamp);
            String responseJson = objectMapper.writeValueAsString(errorResponse);
            ctx.writeAndFlush(new TextWebSocketFrame(responseJson));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}