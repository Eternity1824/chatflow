package com.chatflow.client;

import com.chatflow.protocol.ChatMessage;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class DetailedMetricsCollector {
    private final CountDownLatch responseLatch;
    private final long[] latenciesMs;
    private final long[] ackTimesMs;
    private final AtomicInteger latencyIndex = new AtomicInteger(0);
    private final AtomicInteger ackIndex = new AtomicInteger(0);
    private final LongAdder successCount = new LongAdder();
    private final LongAdder errorResponseCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder totalConnections = new LongAdder();
    private final LongAdder reconnections = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> roomCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> typeCounts = new ConcurrentHashMap<>();
    private final CsvWriter perMessageWriter;

    private volatile long startTimeMs;
    private volatile long endTimeMs;

    public DetailedMetricsCollector(int totalMessages, CountDownLatch responseLatch, String csvPath) throws Exception {
        this.responseLatch = responseLatch;
        this.latenciesMs = new long[totalMessages];
        this.ackTimesMs = new long[totalMessages];
        this.perMessageWriter = new CsvWriter(csvPath,
                "timestamp,messageType,latencyMs,statusCode,roomId");
    }

    public void recordConnection() {
        totalConnections.increment();
    }

    public void recordReconnection() {
        reconnections.increment();
    }

    public void recordFailure() {
        failureCount.increment();
        responseLatch.countDown();
    }

    public void markStart() {
        startTimeMs = System.currentTimeMillis();
    }

    public void markEnd() {
        endTimeMs = System.currentTimeMillis();
    }

    public void recordResponse(long sendTimeMs, ChatMessage.MessageType messageType,
                               long latencyMs, int statusCode, String roomId,
                               long ackTimeMs, String status) {
        if (statusCode == 200) {
            successCount.increment();
        } else {
            errorResponseCount.increment();
        }

        if (latencyMs >= 0) {
            int idx = latencyIndex.getAndIncrement();
            if (idx < latenciesMs.length) {
                latenciesMs[idx] = latencyMs;
            }
        }

        int ackIdx = ackIndex.getAndIncrement();
        if (ackIdx < ackTimesMs.length) {
            ackTimesMs[ackIdx] = ackTimeMs;
        }

        if (roomId != null) {
            roomCounts.computeIfAbsent(roomId, k -> new LongAdder()).increment();
        } else {
            roomCounts.computeIfAbsent("unknown", k -> new LongAdder()).increment();
        }

        String typeKey = messageType != null ? messageType.name() : "UNKNOWN";
        typeCounts.computeIfAbsent(typeKey, k -> new LongAdder()).increment();

        String timestampValue = sendTimeMs >= 0 ? String.valueOf(sendTimeMs) : "";
        String line = String.format("%s,%s,%d,%d,%s",
                timestampValue,
                typeKey,
                latencyMs,
                statusCode,
                roomId == null ? "" : roomId);
        perMessageWriter.writeLine(line);

        responseLatch.countDown();
    }

    public long getTotalRuntimeMs() {
        return endTimeMs - startTimeMs;
    }

    public double getThroughput() {
        long runtime = getTotalRuntimeMs();
        if (runtime <= 0) {
            return 0;
        }
        return (successCount.sum() * 1000.0) / runtime;
    }

    public void printSummary() {
        System.out.println("\n=== Performance Metrics ===");
        System.out.println("Successful messages: " + successCount.sum());
        System.out.println("Error responses: " + errorResponseCount.sum());
        System.out.println("Failed messages: " + failureCount.sum());
        System.out.println("Total runtime: " + getTotalRuntimeMs() + " ms");
        System.out.println("Throughput: " + String.format("%.2f", getThroughput()) + " messages/second");
        System.out.println("Total connections: " + totalConnections.sum());
        System.out.println("Reconnections: " + reconnections.sum());

        printLatencyStats();
        printRoomThroughput();
        printMessageTypeDistribution();
    }

    private void printLatencyStats() {
        int count = Math.min(latencyIndex.get(), latenciesMs.length);
        if (count == 0) {
            System.out.println("Latency stats: no data");
            return;
        }

        long[] copy = Arrays.copyOf(latenciesMs, count);
        Arrays.sort(copy);

        long min = copy[0];
        long max = copy[count - 1];
        double mean = Arrays.stream(copy).average().orElse(0);
        long median = percentile(copy, 0.50);
        long p95 = percentile(copy, 0.95);
        long p99 = percentile(copy, 0.99);

        System.out.println("Latency (ms) mean: " + String.format("%.2f", mean));
        System.out.println("Latency (ms) median: " + median);
        System.out.println("Latency (ms) p95: " + p95);
        System.out.println("Latency (ms) p99: " + p99);
        System.out.println("Latency (ms) min/max: " + min + "/" + max);
    }

    private long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    private void printRoomThroughput() {
        long runtimeMs = getTotalRuntimeMs();
        double runtimeSec = runtimeMs > 0 ? runtimeMs / 1000.0 : 1.0;
        System.out.println("\nThroughput per room (messages/second):");
        roomCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    double tps = entry.getValue().sum() / runtimeSec;
                    System.out.println("  room " + entry.getKey() + ": " + String.format("%.2f", tps));
                });
    }

    private void printMessageTypeDistribution() {
        long total = typeCounts.values().stream().mapToLong(LongAdder::sum).sum();
        if (total == 0) {
            System.out.println("\nMessage type distribution: no data");
            return;
        }
        System.out.println("\nMessage type distribution:");
        typeCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    double pct = (entry.getValue().sum() * 100.0) / total;
                    System.out.println("  " + entry.getKey() + ": " + entry.getValue().sum() +
                            " (" + String.format("%.2f", pct) + "%)");
                });
    }

    public void writeThroughputBuckets(String path) throws Exception {
        int count = Math.min(ackIndex.get(), ackTimesMs.length);
        if (count == 0) {
            return;
        }
        long bucketSizeMs = 10_000L;
        ConcurrentHashMap<Long, LongAdder> buckets = new ConcurrentHashMap<>();
        for (int i = 0; i < count; i++) {
            long ackTime = ackTimesMs[i];
            if (ackTime <= 0) {
                continue;
            }
            long bucketStart = ((ackTime - startTimeMs) / bucketSizeMs) * bucketSizeMs + startTimeMs;
            buckets.computeIfAbsent(bucketStart, k -> new LongAdder()).increment();
        }

        CsvWriter writer = new CsvWriter(path, "bucketStartMs,throughput,count");
        buckets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    long countInBucket = entry.getValue().sum();
                    double throughput = countInBucket / 10.0;
                    String line = entry.getKey() + "," + String.format("%.2f", throughput) + "," + countInBucket;
                    writer.writeLine(line);
                });
        writer.close();
    }

    public void writeSummaryCsv(String path) throws Exception {
        CsvWriter writer = new CsvWriter(path, "metric,value");
        writer.writeLine("successful_messages," + successCount.sum());
        writer.writeLine("error_responses," + errorResponseCount.sum());
        writer.writeLine("failed_messages," + failureCount.sum());
        writer.writeLine("total_runtime_ms," + getTotalRuntimeMs());
        writer.writeLine("throughput_msg_per_sec," + String.format("%.2f", getThroughput()));
        writer.writeLine("total_connections," + totalConnections.sum());
        writer.writeLine("reconnections," + reconnections.sum());
        writer.close();
    }

    public void close() {
        perMessageWriter.close();
    }
}
