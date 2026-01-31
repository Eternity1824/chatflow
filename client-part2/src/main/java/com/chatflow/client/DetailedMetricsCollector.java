package com.chatflow.client;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DetailedMetricsCollector {
    private final AtomicInteger successfulMessages = new AtomicInteger(0);
    private final AtomicInteger failedMessages = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnections = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);

    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    
    private static final long[] BUCKET_THRESHOLDS_MS = {1, 5, 10, 50, 100, 500, 1000, 5000};
    private final ConcurrentHashMap<String, AtomicInteger> latencyBuckets = new ConcurrentHashMap<>();

    public DetailedMetricsCollector() {
        for (long threshold : BUCKET_THRESHOLDS_MS) {
            latencyBuckets.put("<" + threshold + "ms", new AtomicInteger(0));
        }
        latencyBuckets.put(">=5000ms", new AtomicInteger(0));
    }

    public void recordSuccess() {
        successfulMessages.incrementAndGet();
    }

    public void recordFailure() {
        failedMessages.incrementAndGet();
    }

    public void recordConnection() {
        totalConnections.incrementAndGet();
    }

    public void recordReconnection() {
        reconnections.incrementAndGet();
    }

    public void recordLatency(long latencyNs) {
        latencies.add(latencyNs);
        long latencyMs = latencyNs / 1_000_000;
        
        boolean bucketed = false;
        for (long threshold : BUCKET_THRESHOLDS_MS) {
            if (latencyMs < threshold) {
                latencyBuckets.get("<" + threshold + "ms").incrementAndGet();
                bucketed = true;
                break;
            }
        }
        if (!bucketed) {
            latencyBuckets.get(">=5000ms").incrementAndGet();
        }
    }

    public void markStart() {
        startTime.set(System.currentTimeMillis());
    }

    public void markEnd() {
        endTime.set(System.currentTimeMillis());
    }

    public int getSuccessfulMessages() {
        return successfulMessages.get();
    }

    public int getFailedMessages() {
        return failedMessages.get();
    }

    public int getTotalConnections() {
        return totalConnections.get();
    }

    public int getReconnections() {
        return reconnections.get();
    }

    public long getTotalRuntimeMs() {
        return endTime.get() - startTime.get();
    }

    public double getThroughput() {
        long runtime = getTotalRuntimeMs();
        if (runtime == 0) return 0;
        return (successfulMessages.get() * 1000.0) / runtime;
    }

    public void printSummary() {
        System.out.println("\n=== Performance Metrics ===");
        System.out.println("Successful messages: " + getSuccessfulMessages());
        System.out.println("Failed messages: " + getFailedMessages());
        System.out.println("Total runtime: " + getTotalRuntimeMs() + " ms");
        System.out.println("Throughput: " + String.format("%.2f", getThroughput()) + " messages/second");
        System.out.println("Total connections: " + getTotalConnections());
        System.out.println("Reconnections: " + getReconnections());

        if (!latencies.isEmpty()) {
            List<Long> sortedLatencies = new ArrayList<>(latencies);
            Collections.sort(sortedLatencies);

            long p50 = getPercentile(sortedLatencies, 50);
            long p95 = getPercentile(sortedLatencies, 95);
            long p99 = getPercentile(sortedLatencies, 99);
            long min = sortedLatencies.get(0);
            long max = sortedLatencies.get(sortedLatencies.size() - 1);
            double avg = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);

            System.out.println("\n=== Latency Statistics (ms) ===");
            System.out.println("Min: " + String.format("%.2f", min / 1_000_000.0));
            System.out.println("Avg: " + String.format("%.2f", avg / 1_000_000.0));
            System.out.println("Max: " + String.format("%.2f", max / 1_000_000.0));
            System.out.println("P50: " + String.format("%.2f", p50 / 1_000_000.0));
            System.out.println("P95: " + String.format("%.2f", p95 / 1_000_000.0));
            System.out.println("P99: " + String.format("%.2f", p99 / 1_000_000.0));

            System.out.println("\n=== Latency Distribution (Buckets) ===");
            for (long threshold : BUCKET_THRESHOLDS_MS) {
                String key = "<" + threshold + "ms";
                int count = latencyBuckets.get(key).get();
                double percentage = (count * 100.0) / latencies.size();
                System.out.println(String.format("%-10s: %6d (%.2f%%)", key, count, percentage));
            }
            int count = latencyBuckets.get(">=5000ms").get();
            double percentage = (count * 100.0) / latencies.size();
            System.out.println(String.format("%-10s: %6d (%.2f%%)", ">=5000ms", count, percentage));
        }
    }

    private long getPercentile(List<Long> sorted, int percentile) {
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    public void exportToCsv(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            writer.println("metric,value");
            writer.println("successful_messages," + getSuccessfulMessages());
            writer.println("failed_messages," + getFailedMessages());
            writer.println("total_runtime_ms," + getTotalRuntimeMs());
            writer.println("throughput_msg_per_sec," + String.format("%.2f", getThroughput()));
            writer.println("total_connections," + getTotalConnections());
            writer.println("reconnections," + getReconnections());

            if (!latencies.isEmpty()) {
                List<Long> sortedLatencies = new ArrayList<>(latencies);
                Collections.sort(sortedLatencies);

                long p50 = getPercentile(sortedLatencies, 50);
                long p95 = getPercentile(sortedLatencies, 95);
                long p99 = getPercentile(sortedLatencies, 99);
                long min = sortedLatencies.get(0);
                long max = sortedLatencies.get(sortedLatencies.size() - 1);
                double avg = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);

                writer.println("latency_min_ms," + String.format("%.2f", min / 1_000_000.0));
                writer.println("latency_avg_ms," + String.format("%.2f", avg / 1_000_000.0));
                writer.println("latency_max_ms," + String.format("%.2f", max / 1_000_000.0));
                writer.println("latency_p50_ms," + String.format("%.2f", p50 / 1_000_000.0));
                writer.println("latency_p95_ms," + String.format("%.2f", p95 / 1_000_000.0));
                writer.println("latency_p99_ms," + String.format("%.2f", p99 / 1_000_000.0));

                writer.println("\nbucket,count,percentage");
                for (long threshold : BUCKET_THRESHOLDS_MS) {
                    String key = "<" + threshold + "ms";
                    int count = latencyBuckets.get(key).get();
                    double percentage = (count * 100.0) / latencies.size();
                    writer.println(String.format("%s,%d,%.2f", key, count, percentage));
                }
                int count = latencyBuckets.get(">=5000ms").get();
                double percentage = (count * 100.0) / latencies.size();
                writer.println(String.format("%s,%d,%.2f", ">=5000ms", count, percentage));
            }

            System.out.println("Metrics exported to: " + filename);
        } catch (IOException e) {
            System.err.println("Failed to export metrics: " + e.getMessage());
        }
    }
}
