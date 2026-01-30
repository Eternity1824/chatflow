package com.chatflow.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
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

                            // Add WebSocket handler
                            pipeline.addLast(new WebSocketChatHandler());
                        }
                    });

            ChannelFuture future = b.bind(port).sync();
            logger.info("WebSocket Chat Server started on port {}", port);
            logger.info("Connect via: ws://localhost:{}/chat?roomId=<room>", port);

            future.channel().closeFuture().sync();

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        new ChatServer(port).start();
    }
}
