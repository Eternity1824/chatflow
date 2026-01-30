package com.chatflow.client;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketHandshakeHandlerTest {

    @Test
    void handshakeComplete_event_completesPromise_andRemovesHandler() {
        EmbeddedChannel ch = new EmbeddedChannel();
        Promise<Void> promise = ch.eventLoop().newPromise();

        WebSocketHandshakeHandler handler = new WebSocketHandshakeHandler(promise);
        ch.pipeline().addLast(handler);

        ch.pipeline().fireUserEventTriggered(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);

        assertTrue(promise.isSuccess());
        assertNull(ch.pipeline().get(WebSocketHandshakeHandler.class));

        ch.finishAndReleaseAll();
    }
}
