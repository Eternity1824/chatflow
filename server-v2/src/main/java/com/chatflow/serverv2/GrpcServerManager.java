package com.chatflow.serverv2;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GrpcServerManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GrpcServerManager.class);

    private final Server server;
    private final int port;

    public GrpcServerManager(int port, InternalBroadcastGrpcService service) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(service)
                .build();
    }

    public void start() throws IOException {
        server.start();
        logger.info("gRPC server started on port {}", port);
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                logger.info("gRPC server stopped");
            } catch (InterruptedException e) {
                logger.warn("gRPC server shutdown interrupted", e);
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
