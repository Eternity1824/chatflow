package com.chatflow.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class WebSocketClientHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketClientHandler.class);
    private final MetricsCollector metrics;

    public WebSocketClientHandler(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            String response = ((TextWebSocketFrame) frame).text();
            metrics.recordSuccess();
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
