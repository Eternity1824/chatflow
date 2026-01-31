package com.chatflow.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class BackpressureHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        boolean writable = ctx.channel().isWritable();
        // Pause reads when outbound buffer is over high watermark.
        ctx.channel().config().setAutoRead(writable);
        ctx.fireChannelWritabilityChanged();
    }
}
