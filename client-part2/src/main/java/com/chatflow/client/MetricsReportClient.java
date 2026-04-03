package com.chatflow.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches the /api/metrics/report JSON after the load test completes.
 *
 * <h3>URL derivation</h3>
 * Derives the report URL from the WebSocket server URL:
 * <pre>
 *   ws://host:port/chat  →  http://host:port/api/metrics/report
 *   wss://host:port/chat →  https://host:port/api/metrics/report
 * </pre>
 *
 * <h3>Control</h3>
 * Disabled when {@code CHATFLOW_REPORT_ENABLED=false}.
 * Override the URL with {@code CHATFLOW_REPORT_URL}.
 */
public class MetricsReportClient {

    private static final Logger log = LoggerFactory.getLogger(MetricsReportClient.class);
    private static final int TIMEOUT_SECONDS = 30;

    private final boolean enabled;
    private final String  reportBaseUrl;  // http://host:port

    public MetricsReportClient(String wsServerUrl) {
        String envEnabled = System.getenv("CHATFLOW_REPORT_ENABLED");
        this.enabled = !"false".equalsIgnoreCase(envEnabled);

        String override = System.getenv("CHATFLOW_REPORT_URL");
        if (override != null && !override.isBlank()) {
            this.reportBaseUrl = stripTrailingSlash(override);
        } else {
            this.reportBaseUrl = deriveHttpBase(wsServerUrl);
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Fetch and return the metrics report JSON string.
     * Parameters {@code startMs} and {@code endMs} are epoch-milliseconds.
     */
    public String fetchReport(long startMs, long endMs) throws Exception {
        String url = reportBaseUrl + "/api/metrics/report?start=" + startMs + "&end=" + endMs;
        log.info("Fetching metrics report from: {}", url);

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Metrics report HTTP status: {}", response.statusCode());
        return response.body();
    }

    /**
     * Derive the HTTP base URL from a WebSocket URL.
     * Package-private for unit testing.
     */
    static String deriveHttpBase(String wsUrl) {
        if (wsUrl == null || wsUrl.isBlank()) return "http://localhost:8080";
        String http = wsUrl
            .replaceFirst("^wss://", "https://")
            .replaceFirst("^ws://",  "http://");
        // Strip path component
        int pathSlash = http.indexOf('/', http.indexOf("://") + 3);
        if (pathSlash > 0) {
            http = http.substring(0, pathSlash);
        }
        return http;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
