package com.chatflow.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket Chat Server
 */
public class ChatServer {

    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);

    private final int port;
    private final int workerThreads;

    public ChatServer(int port, int workerThreads) {
        this.port = port;
        this.workerThreads = workerThreads;
    }

    public void start() throws InterruptedException {
        boolean useEpoll = Epoll.isAvailable();
        EventLoopGroup bossGroup = useEpoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = createWorkerGroup(useEpoll);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();

                            // HTTP codec
                            pipeline.addLast("httpCodec", new HttpServerCodec());

                            // HTTP aggregator
                            pipeline.addLast("httpAggregator", new HttpObjectAggregator(65536));

                            // Big file upload
                            pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());

                            // Extract roomId from /chat/{roomId} during handshake
                            pipeline.addLast("roomIdExtractor", new RoomIdExtractorHandler());

                            // WebSocket protocol handler(ping, pong, close, etc.)
                            WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                                    .websocketPath("/chat")
                                    .checkStartsWith(true)
                                    .build();
                            pipeline.addLast("webSocketProtocol",
                                    new WebSocketServerProtocolHandler(wsConfig));

                            // Backpressure based on write buffer watermarks.
                            pipeline.addLast("backpressure", new BackpressureHandler());

                            // Add WebSocket handler
                            pipeline.addLast(new WebSocketChatHandler());
                        }
                    });

            ChannelFuture future = b.bind(port).sync();
            logger.info("WebSocket Chat Server started on port {}", port);
            logger.info("Connect via: ws://localhost:{}/chat?roomId=<room>", port);
            logger.info("Transport: {}, workerThreads={}",
                    useEpoll ? "epoll" : "nio",
                    workerThreads > 0 ? workerThreads : "default");

            future.channel().closeFuture().sync();

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup createWorkerGroup(boolean useEpoll) {
        if (useEpoll) {
            return workerThreads > 0 ? new EpollEventLoopGroup(workerThreads) : new EpollEventLoopGroup();
        }
        return workerThreads > 0 ? new NioEventLoopGroup(workerThreads) : new NioEventLoopGroup();
    }

    public static void main(String[] args) throws InterruptedException {
        int port = 8080;
        int workerThreads = 0;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            workerThreads = Integer.parseInt(args[1]);
        }
        new ChatServer(port, workerThreads).start();
    }
}
