package com.chatflow.consumer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MessageDeduplicator {
    private static class Entry {
        private final String messageId;
        private final long timestamp;

        private Entry(String messageId, long timestamp) {
            this.messageId = messageId;
            this.timestamp = timestamp;
        }
    }

    private final ConcurrentHashMap<String, Long> seenMessages = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Entry> timeline = new ConcurrentLinkedQueue<>();
    private final int maxEntries;
    private final long ttlMs;
    private final int cleanupInterval;
    private final AtomicInteger messageCounter = new AtomicInteger(0);
    private volatile long lastCleanupTime = System.currentTimeMillis();

    public MessageDeduplicator(int maxEntries, long ttlMs) {
        this.maxEntries = Math.max(1_000, maxEntries);
        this.ttlMs = Math.max(1_000L, ttlMs);
        this.cleanupInterval = Math.max(100, maxEntries / 20);
    }

    public boolean isDuplicate(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        
        Long existing = seenMessages.putIfAbsent(messageId, now);
        if (existing != null && now - existing <= ttlMs) {
            return true;
        }
        seenMessages.put(messageId, now);
        timeline.offer(new Entry(messageId, now));
        
        if (messageCounter.incrementAndGet() >= cleanupInterval || 
            now - lastCleanupTime > ttlMs / 2) {
            cleanup(now);
            messageCounter.set(0);
            lastCleanupTime = now;
        }
        
        return false;
    }

    private void cleanup(long now) {
        while (true) {
            Entry entry = timeline.peek();
            if (entry == null) {
                break;
            }
            boolean expired = now - entry.timestamp > ttlMs;
            boolean oversized = seenMessages.size() > maxEntries;
            if (!expired && !oversized) {
                break;
            }
            timeline.poll();
            seenMessages.remove(entry.messageId, entry.timestamp);
        }
    }
}
