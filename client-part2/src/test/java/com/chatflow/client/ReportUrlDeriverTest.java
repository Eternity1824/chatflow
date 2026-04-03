package com.chatflow.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetricsReportClient.deriveHttpBase().
 */
class ReportUrlDeriverTest {

    @Test
    void ws_scheme_becomes_http() {
        String result = MetricsReportClient.deriveHttpBase("ws://host:8080/chat");
        assertTrue(result.startsWith("http://"), "Expected http:// but got: " + result);
        assertFalse(result.startsWith("https://"), "Should not be https://");
    }

    @Test
    void wss_scheme_becomes_https() {
        String result = MetricsReportClient.deriveHttpBase("wss://host:443/chat");
        assertTrue(result.startsWith("https://"), "Expected https:// but got: " + result);
    }

    @Test
    void path_is_stripped() {
        String result = MetricsReportClient.deriveHttpBase("ws://myhost:8080/chat");
        assertEquals("http://myhost:8080", result);
    }

    @Test
    void ws_host_port_only_no_path() {
        String result = MetricsReportClient.deriveHttpBase("ws://myhost:9090");
        assertEquals("http://myhost:9090", result);
    }

    @Test
    void ws_host_port_with_chat_path() {
        String result = MetricsReportClient.deriveHttpBase("ws://myhost:8080/chat");
        assertEquals("http://myhost:8080", result);
    }

    @Test
    void ws_host_port_with_nested_path() {
        String result = MetricsReportClient.deriveHttpBase("ws://myhost:8080/chat/room1");
        assertEquals("http://myhost:8080", result);
    }

    @Test
    void wss_host_port_with_chat_path() {
        String result = MetricsReportClient.deriveHttpBase("wss://secure.example.com:443/chat");
        assertEquals("https://secure.example.com:443", result);
    }

    @Test
    void null_input_returns_localhost_default() {
        String result = MetricsReportClient.deriveHttpBase(null);
        assertEquals("http://localhost:8080", result);
    }

    @Test
    void blank_input_returns_localhost_default() {
        String result = MetricsReportClient.deriveHttpBase("  ");
        assertEquals("http://localhost:8080", result);
    }

    @Test
    void ws_with_ip_address() {
        String result = MetricsReportClient.deriveHttpBase("ws://192.168.1.100:8080/chat");
        assertEquals("http://192.168.1.100:8080", result);
    }
}
