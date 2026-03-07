package com.chatflow.consumer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ConsumerMetrics {
    private final Instant startedAt = Instant.now();
    private final AtomicLong messagesPolled = new AtomicLong();
    private final AtomicLong messagesBroadcast = new AtomicLong();
    private final AtomicLong messagesAcked = new AtomicLong();
    private final AtomicLong messagesRetried = new AtomicLong();
    private final AtomicLong messagesDropped = new AtomicLong();
    private final AtomicLong messagesDuplicate = new AtomicLong();
    private final AtomicLong dedupeChecks = new AtomicLong();
    private final AtomicLong retriesExhausted = new AtomicLong();
    private final AtomicLong broadcastFailures = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> roomDeliveries = new ConcurrentHashMap<>();

    public void recordPolled() {
        messagesPolled.incrementAndGet();
    }

    public void recordBroadcast(String roomId) {
        messagesBroadcast.incrementAndGet();
        roomDeliveries.computeIfAbsent(roomId, key -> new AtomicLong()).incrementAndGet();
    }

    public void recordAcked() {
        messagesAcked.incrementAndGet();
    }

    public void recordRetried() {
        messagesRetried.incrementAndGet();
    }

    public void recordDropped() {
        messagesDropped.incrementAndGet();
    }

    public void recordDuplicate() {
        messagesDuplicate.incrementAndGet();
    }

    public void recordDedupeCheck() {
        dedupeChecks.incrementAndGet();
    }

    public void recordRetriesExhausted() {
        retriesExhausted.incrementAndGet();
    }

    public void recordBroadcastFailure() {
        broadcastFailures.incrementAndGet();
    }

    public void recordParseError() {
        parseErrors.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("startedAt", startedAt.toString());
        snapshot.put("messagesPolled", messagesPolled.get());
        snapshot.put("messagesBroadcast", messagesBroadcast.get());
        snapshot.put("messagesAcked", messagesAcked.get());
        snapshot.put("messagesRetried", messagesRetried.get());
        snapshot.put("messagesDropped", messagesDropped.get());
        snapshot.put("messagesDuplicate", messagesDuplicate.get());
        snapshot.put("dedupeChecks", dedupeChecks.get());
        snapshot.put("retriesExhausted", retriesExhausted.get());
        snapshot.put("broadcastFailures", broadcastFailures.get());
        snapshot.put("parseErrors", parseErrors.get());
        Map<String, Long> roomSnapshot = new LinkedHashMap<>();
        roomDeliveries.forEach((roomId, count) -> roomSnapshot.put(roomId, count.get()));
        snapshot.put("roomDeliveries", roomSnapshot);
        return snapshot;
    }
}
