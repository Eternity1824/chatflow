package com.chatflow.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.concurrent.Promise;

public final class WebSocketHandshakeHandler extends ChannelInboundHandlerAdapter {
    private final Promise<Void> handshakePromise;

    public WebSocketHandshakeHandler(Promise<Void> handshakePromise) {
        this.handshakePromise = handshakePromise;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent) {
            WebSocketClientProtocolHandler.ClientHandshakeStateEvent state =
                    (WebSocketClientProtocolHandler.ClientHandshakeStateEvent) evt;
            if (state == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                if (!handshakePromise.isDone()) {
                    handshakePromise.setSuccess(null);
                }
                ctx.pipeline().remove(this);
                return;
            }
            if (state == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED) {
                return;
            }
            if (!handshakePromise.isDone()) {
                handshakePromise.setFailure(new IllegalStateException("Handshake failed: " + state));
            }
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!handshakePromise.isDone()) {
            handshakePromise.setFailure(cause);
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!handshakePromise.isDone()) {
            handshakePromise.setFailure(new IllegalStateException("Channel inactive before handshake completed"));
        }
    }
}
