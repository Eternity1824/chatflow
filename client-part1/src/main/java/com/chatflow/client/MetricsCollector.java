package com.chatflow.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {
    private final AtomicInteger successfulMessages = new AtomicInteger(0);
    private final AtomicInteger failedMessages = new AtomicInteger(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger reconnections = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);

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
    }
}
