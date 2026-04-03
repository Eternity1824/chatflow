package com.chatflow.consumerv3;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchAccumulatorTest {

    private static QueueEnvelope envelope(long tag) {
        return new QueueEnvelope(new byte[0], tag, "room.1", "room.1");
    }

    // ── add / isReady (size trigger) ──────────────────────────────────────────

    @Test
    void add_belowBatchSize_notFull() {
        BatchAccumulator acc = new BatchAccumulator(3, 60_000);
        assertFalse(acc.add(envelope(1)));
        assertFalse(acc.add(envelope(2)));
        assertEquals(2, acc.size());
    }

    @Test
    void add_atBatchSize_returnsTrueAndIsReady() {
        BatchAccumulator acc = new BatchAccumulator(3, 60_000);
        acc.add(envelope(1));
        acc.add(envelope(2));
        assertTrue(acc.add(envelope(3)));   // triggers on 3rd
        assertTrue(acc.isReady());
    }

    // ── drain ─────────────────────────────────────────────────────────────────

    @Test
    void drain_returnsAllEnvelopes_andClearsBuffer() {
        BatchAccumulator acc = new BatchAccumulator(5, 60_000);
        acc.add(envelope(10));
        acc.add(envelope(20));

        List<QueueEnvelope> batch = acc.drain();
        assertEquals(2, batch.size());
        assertEquals(10L, batch.get(0).getDeliveryTag());
        assertEquals(20L, batch.get(1).getDeliveryTag());

        assertEquals(0, acc.size());
        assertFalse(acc.isReady());
    }

    @Test
    void drain_emptyAccumulator_returnsEmptyList() {
        BatchAccumulator acc = new BatchAccumulator(5, 60_000);
        List<QueueEnvelope> batch = acc.drain();
        assertTrue(batch.isEmpty());
    }

    @Test
    void drain_twiceConcurrently_secondDrainGetsEmpty() {
        BatchAccumulator acc = new BatchAccumulator(5, 60_000);
        acc.add(envelope(1));

        List<QueueEnvelope> first  = acc.drain();
        List<QueueEnvelope> second = acc.drain();

        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
    }

    // ── isReady (time trigger) ────────────────────────────────────────────────

    @Test
    void isReady_emptyBuffer_alwaysFalse() throws InterruptedException {
        BatchAccumulator acc = new BatchAccumulator(100, 1);  // 1ms flush
        Thread.sleep(5);
        assertFalse(acc.isReady(), "empty accumulator must never be ready");
    }

    @Test
    void isReady_afterFlushInterval_returnsTrue() throws InterruptedException {
        BatchAccumulator acc = new BatchAccumulator(100, 10);  // 10ms flush
        acc.add(envelope(1));
        Thread.sleep(30);
        assertTrue(acc.isReady(), "should be ready after flush interval elapsed");
    }

    @Test
    void isReady_beforeFlushInterval_returnsFalse() {
        BatchAccumulator acc = new BatchAccumulator(100, 60_000);  // 60s flush
        acc.add(envelope(1));
        assertFalse(acc.isReady(), "not ready before flush interval");
    }

    // ── Immutability of drained list ──────────────────────────────────────────

    @Test
    void drain_returnedList_isImmutable() {
        BatchAccumulator acc = new BatchAccumulator(5, 60_000);
        acc.add(envelope(1));
        List<QueueEnvelope> batch = acc.drain();
        assertThrows(UnsupportedOperationException.class, () -> batch.add(envelope(99)));
    }
}
