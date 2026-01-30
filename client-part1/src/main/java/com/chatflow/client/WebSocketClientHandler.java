package com.chatflow.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

public class WebSocketClientHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
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
        System.err.println("WebSocket error: " + cause.getMessage());
        ctx.close();
    }
}
