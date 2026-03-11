package com.chatflow.consumer;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MessageDeduplicator {
    private static class Entry {
        private String messageId;
        private long timestamp;

        private Entry() {
        }

        private void reset(String messageId, long timestamp) {
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
    private final Object cleanupLock = new Object();
    
    private final ArrayDeque<Entry> entryPool = new ArrayDeque<>();
    private static final int MAX_POOL_SIZE = 1000;

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
        
        Entry entry = acquireEntry();
        entry.reset(messageId, now);
        timeline.offer(entry);
        
        if (messageCounter.incrementAndGet() >= cleanupInterval ||
            now - lastCleanupTime > ttlMs / 2) {
            synchronized (cleanupLock) {
                // Re-check under lock to avoid duplicate concurrent cleanup passes.
                if (messageCounter.get() >= cleanupInterval ||
                    now - lastCleanupTime > ttlMs / 2) {
                    cleanup(now);
                    messageCounter.set(0);
                    lastCleanupTime = now;
                }
            }
        }
        
        return false;
    }
    
    private Entry acquireEntry() {
        synchronized (entryPool) {
            Entry entry = entryPool.pollFirst();
            if (entry != null) {
                return entry;
            }
        }
        return new Entry();
    }
    
    private void releaseEntry(Entry entry) {
        if (entry == null) {
            return;
        }
        synchronized (entryPool) {
            if (entryPool.size() < MAX_POOL_SIZE) {
                entry.reset(null, 0);
                entryPool.offerLast(entry);
            }
        }
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
            entry = timeline.poll();
            if (entry != null) {
                seenMessages.remove(entry.messageId, entry.timestamp);
                releaseEntry(entry);
            }
        }
    }
}
