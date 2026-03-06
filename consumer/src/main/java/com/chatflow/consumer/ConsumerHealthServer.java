package com.chatflow.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class ConsumerHealthServer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerHealthServer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final int port;
    private final ConsumerMetrics metrics;
    private HttpServer server;

    public ConsumerHealthServer(int port, ConsumerMetrics metrics) {
        this.port = port;
        this.metrics = metrics;
    }

    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/metrics", exchange -> {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(metrics.snapshot());
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        logger.info("Consumer health server started on port {}", port);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            logger.info("Consumer health server stopped");
        }
    }
}
