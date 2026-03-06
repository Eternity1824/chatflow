package com.chatflow.serverv2;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class BackpressureHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        boolean writable = ctx.channel().isWritable();
        ctx.channel().config().setAutoRead(writable);
        ctx.fireChannelWritabilityChanged();
    }
}
