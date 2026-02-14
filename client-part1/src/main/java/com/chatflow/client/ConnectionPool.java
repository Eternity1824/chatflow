package com.chatflow.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.util.concurrent.Promise;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ConnectionPool {
    private final ConcurrentHashMap<String, Channel> roomChannels;
    private final ConcurrentHashMap<String, CompletableFuture<Channel>> inFlightConnections;
    private final ConcurrentHashMap<String, AtomicInteger> roomCounters;
    private final String serverUrl;
    private final EventLoopGroup eventLoopGroup;
    private final MetricsCollector metrics;
    private final Bootstrap bootstrap;
    private final CountDownLatch responseLatch;
    private final int connectionsPerRoom;
    private final long handshakeTimeoutSeconds;
    private final long retryDelayMs;
    private final int connectTimeoutMillis;
    private final Semaphore handshakeSemaphore;

    public ConnectionPool(String serverUrl, EventLoopGroup eventLoopGroup, MetricsCollector metrics,
                          CountDownLatch responseLatch, int connectionsPerRoom,
                          int handshakeTimeoutSeconds, int maxConcurrentHandshakes,
                          int handshakeRetryDelayMs, ChannelFactory<? extends Channel> channelFactory) {
        this.serverUrl = serverUrl;
        this.eventLoopGroup = eventLoopGroup;
        this.metrics = metrics;
        this.responseLatch = responseLatch;
        this.connectionsPerRoom = Math.max(1, connectionsPerRoom);
        this.handshakeTimeoutSeconds = Math.max(1, handshakeTimeoutSeconds);
        this.retryDelayMs = Math.max(1, handshakeRetryDelayMs);
        int boundedConcurrentHandshakes = Math.max(1, maxConcurrentHandshakes);
        this.connectTimeoutMillis = (int) TimeUnit.SECONDS.toMillis(this.handshakeTimeoutSeconds);
        this.roomChannels = new ConcurrentHashMap<>();
        this.inFlightConnections = new ConcurrentHashMap<>();
        this.roomCounters = new ConcurrentHashMap<>();
        this.bootstrap = createBootstrap(channelFactory);
        this.handshakeSemaphore = new Semaphore(boundedConcurrentHandshakes);
    }

    private Bootstrap createBootstrap(ChannelFactory<? extends Channel> channelFactory) {
        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup).channelFactory(channelFactory);
        b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        return b;
    }

    public Channel getOrCreateConnection(String roomId) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        int index = roomCounters.computeIfAbsent(roomId, key -> new AtomicInteger())
                .getAndIncrement();
        String key = connectionKey(roomId, index % connectionsPerRoom);

        Channel existing = roomChannels.get(key);
        if (existing != null && existing.isActive()) {
            return existing;
        }

        if (existing != null) {
            metrics.recordReconnection();
            roomChannels.remove(key, existing);
        }

        CompletableFuture<Channel> newFuture = new CompletableFuture<>();
        CompletableFuture<Channel> inFlight = inFlightConnections.putIfAbsent(key, newFuture);
        if (inFlight == null) {
            inFlight = newFuture;
            boolean acquired = false;
            try {
                while (!acquired && !Thread.currentThread().isInterrupted()) {
                    acquired = handshakeSemaphore.tryAcquire(retryDelayMs, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            try {
                if (!acquired) {
                    throw new InterruptedException("Failed to acquire handshake permit");
                }
                Channel channel = connect(roomId);
                roomChannels.put(key, channel);
                metrics.recordConnection();
                inFlight.complete(channel);
            } catch (Exception e) {
                inFlight.completeExceptionally(e);
            } finally {
                if (acquired) {
                    handshakeSemaphore.release();
                }
                inFlightConnections.remove(key, inFlight);
            }
        }

        try {
            return inFlight.get(handshakeTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            inFlightConnections.remove(key, inFlight);
            throw new IllegalStateException("Handshake timed out for roomId=" + roomId, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    Channel connect(String roomId) throws Exception {
        URI uri = new URI(serverUrl + "/" + roomId);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 80;

        AtomicReference<Promise<Void>> handshakePromiseRef = new AtomicReference<>();

        Bootstrap b = bootstrap.clone();
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                Promise<Void> handshakePromise = ch.eventLoop().newPromise();
                handshakePromiseRef.set(handshakePromise);

                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast(new HttpClientCodec());
                pipeline.addLast(new HttpObjectAggregator(65536));
                WebSocketClientProtocolHandler wsHandler = new WebSocketClientProtocolHandler(
                        WebSocketClientHandshakerFactory.newHandshaker(
                                uri, WebSocketVersion.V13, null, false,
                                new DefaultHttpHeaders()));
                pipeline.addLast(wsHandler);
                pipeline.addLast(new WebSocketHandshakeHandler(handshakePromise));
                pipeline.addLast(new WebSocketClientHandler(metrics, responseLatch));
            }
        });

        Channel channel = b.connect(host, port).sync().channel();
        try {
            Promise<Void> handshakePromise = handshakePromiseRef.get();
            if (handshakePromise == null) {
                throw new IllegalStateException("Handshake promise not initialized");
            }
            boolean completed = handshakePromise.await(handshakeTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                throw new IllegalStateException("Handshake timed out after " + handshakeTimeoutSeconds + "s");
            }
            handshakePromise.sync();
        } catch (Exception e) {
            channel.close();
            throw e;
        }
        return channel;
    }

    public void removeConnection(String roomId) {
        String prefix = roomId + "-";
        roomChannels.forEach((key, channel) -> {
            if (key.startsWith(prefix)) {
                if (roomChannels.remove(key, channel)) {
                    channel.close();
                }
            }
        });
        inFlightConnections.forEach((key, inFlight) -> {
            if (key.startsWith(prefix)) {
                if (inFlightConnections.remove(key, inFlight)) {
                    inFlight.cancel(false);
                }
            }
        });
    }

    public void closeAll() {
        for (Channel channel : roomChannels.values()) {
            if (channel != null) {
                channel.close();
            }
        }
        roomChannels.clear();
        roomCounters.clear();
        for (CompletableFuture<Channel> inFlight : inFlightConnections.values()) {
            if (inFlight != null) {
                inFlight.cancel(false);
            }
        }
        inFlightConnections.clear();
    }

    private String connectionKey(String roomId, int index) {
        return roomId + "-" + index;
    }
}
