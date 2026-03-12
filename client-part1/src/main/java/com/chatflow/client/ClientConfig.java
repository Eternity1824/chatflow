package com.chatflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;

public class ClientConfig {
    private String jvmOpts = "-Xms512m -Xmx1g";
    private ClientSettings client = new ClientSettings();

    public String getJvmOpts() {
        return jvmOpts;
    }

    public ClientSettings getClient() {
        return client;
    }

    public static ClientConfig load(String path) {
        if (path == null || path.isBlank()) {
            return new ClientConfig();
        }
        File file = new File(path);
        if (!file.exists()) {
            return new ClientConfig();
        }
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return mapper.readValue(file, ClientConfig.class);
        } catch (Exception e) {
            return new ClientConfig();
        }
    }

    public static class ClientSettings {
        private String serverUrl = "ws://localhost:8080/chat";
        private int totalMessages = 500_000;
        private int warmupThreads = 32;
        private int warmupMessagesPerThread = 1000;
        private int connectionsPerRoom = 1;
        private int handshakeTimeoutSeconds = 20;
        private int maxConcurrentHandshakes = 6;
        private int handshakeRetryDelayMs = 10;
        private int responseWaitSeconds = 30;
        private int batchSize = 100;
        private int batchMaxBytes = 65536;
        private int flushIntervalMs = 2;
        private boolean flushSync = false;
        private int mainThreads = 0;
        private int targetQps = 0;

        public String getServerUrl() {
            return serverUrl;
        }

        public int getTotalMessages() {
            return totalMessages;
        }

        public int getWarmupThreads() {
            return warmupThreads;
        }

        public int getWarmupMessagesPerThread() {
            return warmupMessagesPerThread;
        }

        public int getConnectionsPerRoom() {
            return connectionsPerRoom;
        }

        public int getHandshakeTimeoutSeconds() {
            return handshakeTimeoutSeconds;
        }

        public int getMaxConcurrentHandshakes() {
            return maxConcurrentHandshakes;
        }

        public int getHandshakeRetryDelayMs() {
            return handshakeRetryDelayMs;
        }

        public int getResponseWaitSeconds() {
            return responseWaitSeconds;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public int getBatchMaxBytes() {
            return batchMaxBytes;
        }

        public int getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public boolean isFlushSync() {
            return flushSync;
        }

        public int getMainThreads() {
            return mainThreads;
        }

        public int getTargetQps() {
            return targetQps;
        }
    }
}
