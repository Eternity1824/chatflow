package com.chatflow.consumer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RoomSequenceManager {
    private final ConcurrentHashMap<String, AtomicLong> roomCounters = new ConcurrentHashMap<>();

    public long nextSequence(String roomId) {
        AtomicLong counter = roomCounters.computeIfAbsent(roomId, key -> new AtomicLong(0));
        return counter.incrementAndGet();
    }
}
