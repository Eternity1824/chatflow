package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;
import com.chatflow.protocol.ServerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;

public class WebSocketClientHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketClientHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final DetailedMetricsCollector metrics;
    private final ConnectionPool connectionPool;

    public WebSocketClientHandler(DetailedMetricsCollector metrics, ConnectionPool connectionPool) {
        this.metrics = metrics;
        this.connectionPool = connectionPool;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame)) {
            return;
        }
        String responseText = ((TextWebSocketFrame) frame).text();

        try {
            ServerResponse response = objectMapper.readValue(responseText, ServerResponse.class);
            String status = response.getStatus();
            int statusCode = "success".equalsIgnoreCase(status) ? 200 : 400;

            ChatMessage original = response.getOriginalMessage();
            ChatMessage.MessageType messageType = original != null ? original.getMessageType() : null;

            long sendTimestampMs = -1;
            if (original != null && original.getTimestamp() != null) {
                try {
                    sendTimestampMs = Instant.parse(original.getTimestamp()).toEpochMilli();
                } catch (Exception e) {
                    logger.warn("Failed to parse timestamp: {}", original.getTimestamp());
                }
            }

            long ackTimeMs = System.currentTimeMillis();
            long latencyMs = sendTimestampMs >= 0 ? ackTimeMs - sendTimestampMs : -1;
            String roomId = connectionPool.getRoomIdForChannel(ctx.channel());

            metrics.recordResponse(sendTimestampMs, messageType, latencyMs, statusCode, roomId, ackTimeMs, status);
        } catch (Exception e) {
            logger.warn("Failed to parse server response", e);
            metrics.recordFailure();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String channelId = ctx.channel().id().asShortText();
        MDC.put("channelId", channelId);
        try {
            logger.warn("WebSocket error", cause);
        } finally {
            MDC.remove("channelId");
        }
        ctx.close();
    }
}
