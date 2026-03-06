package com.chatflow.consumer;

import com.chatflow.protocol.QueueChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class BroadcastClient {
    private static final Logger logger = LoggerFactory.getLogger(BroadcastClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final List<String> targets;
    private final String internalToken;
    private final long timeoutMs;

    public BroadcastClient(List<String> targets, String internalToken, long timeoutMs) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500L, timeoutMs)))
                .build();
        this.targets = targets;
        this.internalToken = internalToken;
        this.timeoutMs = Math.max(500L, timeoutMs);
    }

    public boolean broadcast(QueueChatMessage message) {
        if (targets == null || targets.isEmpty()) {
            logger.error("No broadcast targets configured. Set CHATFLOW_BROADCAST_TARGETS.");
            return false;
        }

        String payload;
        try {
            payload = OBJECT_MAPPER.writeValueAsString(message);
        } catch (Exception e) {
            logger.warn("Failed to serialize queue message {}", message.getMessageId(), e);
            return false;
        }

        boolean allSucceeded = true;
        for (String target : targets) {
            try {
                URI uri = resolveInternalBroadcastUri(target);
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));

                if (internalToken != null && !internalToken.isBlank()) {
                    requestBuilder.header("X-Chatflow-Token", internalToken);
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    logger.warn("Broadcast target {} responded with status {}", uri, statusCode);
                    allSucceeded = false;
                }
            } catch (Exception e) {
                logger.warn("Failed broadcasting message {} to target {}", message.getMessageId(), target, e);
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }

    private URI resolveInternalBroadcastUri(String target) {
        String normalized = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
        if (normalized.endsWith("/internal/broadcast")) {
            return URI.create(normalized);
        }
        return URI.create(normalized + "/internal/broadcast");
    }
}
