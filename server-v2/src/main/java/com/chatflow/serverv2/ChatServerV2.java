package com.chatflow.serverv2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
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

public class ChatServerV2 {
    private static final Logger logger = LoggerFactory.getLogger(ChatServerV2.class);

    private final int port;
    private final int workerThreads;
    private final String serverId;
    private final String internalToken;
    private final RabbitMqPublisher publisher;
    private RoomSessionRegistry roomSessionRegistry;

    public ChatServerV2(
            int port,
            int workerThreads,
            String serverId,
            String internalToken,
            RabbitMqPublisher publisher) {
        this.port = port;
        this.workerThreads = workerThreads;
        this.serverId = serverId;
        this.internalToken = internalToken;
        this.publisher = publisher;
    }

    public void start() throws Exception {
        boolean useEpoll = Epoll.isAvailable();
        EventLoopGroup bossGroup = useEpoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = createWorkerGroup(useEpoll);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.AUTO_READ, true)
                    .childOption(
                            ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast("httpCodec", new HttpServerCodec());
                            pipeline.addLast("httpAggregator", new HttpObjectAggregator(65536));
                            pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                            pipeline.addLast("roomIdExtractor", new RoomIdExtractorHandler());
                            pipeline.addLast(
                                    "internalBroadcast",
                                    new InternalBroadcastHandler(roomSessionRegistry, internalToken));

                            WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                                    .websocketPath("/chat")
                                    .checkStartsWith(true)
                                    .build();
                            pipeline.addLast("webSocketProtocol", new WebSocketServerProtocolHandler(wsConfig));
                            pipeline.addLast("backpressure", new BackpressureHandler());
                            pipeline.addLast(
                                    "chatHandler",
                                    new WebSocketChatHandlerV2(publisher, serverId, roomSessionRegistry));
                        }
                    });

            ChannelFuture future = bootstrap.bind(port).sync();
            Channel serverChannel = future.channel();
            logger.info("ChatServerV2 started on port {}", port);
            logger.info("Server ID: {}", serverId);
            logger.info("Connect via: ws://localhost:{}/chat?roomId=<room>", port);
            logger.info("Internal broadcast endpoint: http://localhost:{}/internal/broadcast", port);
            serverChannel.closeFuture().sync();
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

    public static void main(String[] args) throws Exception {
        int port = 8080;
        int grpcPort = 9090;
        int workerThreads = 0;
        String serverId = System.getenv().getOrDefault("CHATFLOW_SERVER_ID", "server-v2-local");
        String internalToken = System.getenv().getOrDefault("CHATFLOW_INTERNAL_TOKEN", "");

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length > 1) {
            workerThreads = Integer.parseInt(args[1]);
        }
        if (args.length > 2) {
            serverId = args[2];
        }
        if (args.length > 3) {
            internalToken = args[3];
        }
        if (args.length > 4) {
            grpcPort = Integer.parseInt(args[4]);
        }

        RabbitMqConfig config = RabbitMqConfig.fromEnvironment();
        RoomSessionRegistry roomSessionRegistry = new RoomSessionRegistry();
        
        InternalBroadcastGrpcService grpcService = new InternalBroadcastGrpcService(roomSessionRegistry, internalToken);
        GrpcServerManager grpcServer = new GrpcServerManager(grpcPort, grpcService);
        grpcServer.start();
        logger.info("gRPC internal broadcast service available on port {}", grpcPort);

        try (RabbitMqPublisher publisher = new RabbitMqPublisher(config)) {
            ChatServerV2 server = new ChatServerV2(port, workerThreads, serverId, internalToken, publisher);
            server.roomSessionRegistry = roomSessionRegistry;
            server.start();
        } finally {
            grpcServer.close();
        }
    }
}
