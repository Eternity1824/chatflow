package com.chatflow.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class RoomIdExtractorHandlerTest {

    @Test
    void queryRoomId_setsChannelAttribute_andForwardsRequest() {
        AtomicReference<String> roomIdSeen = new AtomicReference<>();
        AtomicReference<String> forwardedUri = new AtomicReference<>();

        EmbeddedChannel ch = new EmbeddedChannel(
                new RoomIdExtractorHandler(),
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                        roomIdSeen.set(ctx.channel().attr(RoomIdExtractorHandler.ROOM_ID_ATTR).get());
                        forwardedUri.set(msg.uri());
                    }
                }
        );

        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat?roomId=room1");
        ch.writeInbound(req);
        ch.runPendingTasks();

        assertEquals("room1", roomIdSeen.get());
        assertEquals("/chat?roomId=room1", forwardedUri.get());

        assertNull(ch.readOutbound());
        ch.finishAndReleaseAll();
    }

    @Test
    void pathRoomId_setsChannelAttribute_andForwardsRequest() {
        AtomicReference<String> roomIdSeen = new AtomicReference<>();

        EmbeddedChannel ch = new EmbeddedChannel(
                new RoomIdExtractorHandler(),
                new SimpleChannelInboundHandler<FullHttpRequest>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
                        roomIdSeen.set(ctx.channel().attr(RoomIdExtractorHandler.ROOM_ID_ATTR).get());
                    }
                }
        );

        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat/room2");
        ch.writeInbound(req);
        ch.runPendingTasks();

        assertEquals("room2", roomIdSeen.get());
        assertNull(ch.readOutbound());
        ch.finishAndReleaseAll();
    }

    @Test
    void missingRoomId_returnsBadRequest() {
        EmbeddedChannel ch = new EmbeddedChannel(new RoomIdExtractorHandler());

        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/chat");
        ch.writeInbound(req);
        ch.runPendingTasks();

        Object outbound = ch.readOutbound();
        assertNotNull(outbound);
        assertTrue(outbound instanceof FullHttpResponse);

        FullHttpResponse resp = (FullHttpResponse) outbound;
        assertEquals(HttpResponseStatus.BAD_REQUEST, resp.status());

        ch.runPendingTasks();
        ch.finishAndReleaseAll();
    }
}
