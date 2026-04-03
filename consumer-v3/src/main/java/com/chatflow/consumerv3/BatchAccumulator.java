package com.chatflow.consumerv3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Collects {@link QueueEnvelope}s into fixed-size or time-bounded batches.
 *
 * <p>Design intent:
 * <ul>
 *   <li>A single virtual thread (or event-loop thread) calls {@link #add}.</li>
 *   <li>When {@link #isReady()} returns {@code true} the caller drains the
 *       batch via {@link #drain()} and hands it to {@link PersistenceWriter}.</li>
 *   <li>A background flush timer also calls {@link #drain()} after
 *       {@code flushIntervalMs} to avoid long tail delays on quiet queues.</li>
 * </ul>
 *
 * TODO (Phase 2): implement actual accumulation logic, flush-timer scheduling,
 * and thread-safe drain semantics.
 */
public class BatchAccumulator {

    private final int  maxBatchSize;
    private final long flushIntervalMs;

    private final List<QueueEnvelope> buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private long lastFlushAt;

    public BatchAccumulator(int maxBatchSize, long flushIntervalMs) {
        this.maxBatchSize    = maxBatchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.buffer          = new ArrayList<>(maxBatchSize);
        this.lastFlushAt     = System.currentTimeMillis();
    }

    /**
     * Add a single envelope to the current batch.
     *
     * @return {@code true} if the batch is now full and should be drained immediately
     */
    public boolean add(QueueEnvelope envelope) {
        lock.lock();
        try {
            buffer.add(envelope);
            return buffer.size() >= maxBatchSize;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if the batch should be flushed — either it has
     * reached {@code maxBatchSize} or the flush timer has expired.
     */
    public boolean isReady() {
        lock.lock();
        try {
            if (buffer.isEmpty()) return false;
            if (buffer.size() >= maxBatchSize) return true;
            return (System.currentTimeMillis() - lastFlushAt) >= flushIntervalMs;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically drain and return the current batch, resetting internal state.
     * Returns an empty list if the buffer is empty.
     */
    public List<QueueEnvelope> drain() {
        lock.lock();
        try {
            if (buffer.isEmpty()) return List.of();
            List<QueueEnvelope> batch = List.copyOf(buffer);  // unmodifiable snapshot
            buffer.clear();
            lastFlushAt = System.currentTimeMillis();
            return batch;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return buffer.size(); }
        finally { lock.unlock(); }
    }
}
