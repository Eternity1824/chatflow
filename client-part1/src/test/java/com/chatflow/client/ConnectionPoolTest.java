package com.chatflow.client;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ConnectionPoolTest {

    private NioEventLoopGroup group;

    @AfterEach
    void tearDown() {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @Test
    void sameRoomId_concurrentGetOrCreate_onlyConnectsOnce() throws Exception {
        group = new NioEventLoopGroup(1);
        MetricsCollector metrics = new MetricsCollector();

        AtomicInteger connectCalls = new AtomicInteger(0);
        CountDownLatch connectEntered = new CountDownLatch(1);
        CountDownLatch allowConnectReturn = new CountDownLatch(1);

        ConnectionPool pool = new ConnectionPool("ws://localhost:8080/chat", group, metrics,
                new CountDownLatch(0), 1, 10, 4, 5, NioSocketChannel::new) {
            @Override
            Channel connect(String roomId) throws Exception {
                connectCalls.incrementAndGet();
                connectEntered.countDown();
                assertTrue(allowConnectReturn.await(2, TimeUnit.SECONDS));
                return new EmbeddedChannel();
            }
        };

        int threads = 10;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Channel>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(exec.submit(() -> pool.getOrCreateConnection("room1")));
            }

            assertTrue(connectEntered.await(2, TimeUnit.SECONDS));
            allowConnectReturn.countDown();

            Channel first = futures.get(0).get(2, TimeUnit.SECONDS);
            assertNotNull(first);
            for (Future<Channel> f : futures) {
                assertSame(first, f.get(2, TimeUnit.SECONDS));
            }

            assertEquals(1, connectCalls.get());
        } finally {
            exec.shutdownNow();
        }
    }
}
