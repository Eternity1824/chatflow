package com.chatflow.consumerv3;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-process metrics for consumer-v3.
 * All counters use {@link LongAdder} for lock-free updates from virtual threads.
 */
public class ConsumerMetrics {

    private static final Logger log = LogManager.getLogger(ConsumerMetrics.class);

    // ── Ingestion ─────────────────────────────────────────────────────────────
    private final LongAdder messagesReceived   = new LongAdder();
    private final LongAdder messagesParsed     = new LongAdder();
    private final LongAdder parseFailures      = new LongAdder();

    // ── Batch ─────────────────────────────────────────────────────────────────
    private final LongAdder batchesFlushed     = new LongAdder();
    private final LongAdder totalBatchSize     = new LongAdder();

    // ── DynamoDB write results ────────────────────────────────────────────────
    private final LongAdder recordsWritten     = new LongAdder();
    private final LongAdder duplicateMessages  = new LongAdder();
    /** Transient failures resolved by internal retry (did NOT reach TERMINAL). */
    private final LongAdder transientFailures  = new LongAdder();
    /** Terminal failures that could not be recovered by retry. */
    private final LongAdder terminalFailures   = new LongAdder();
    /** Total retry attempts across all records. */
    private final LongAdder retries            = new LongAdder();

    // ── DLQ ───────────────────────────────────────────────────────────────────
    private final LongAdder dlqPublished       = new LongAdder();
    private final LongAdder dlqPublishFailures = new LongAdder();

    // ── Circuit breaker ───────────────────────────────────────────────────────
    /** Number of times a flush was skipped because the circuit breaker was open. */
    private final LongAdder cbSkips            = new LongAdder();

    // ── ACK / NACK ────────────────────────────────────────────────────────────
    private final LongAdder acksSucceeded      = new LongAdder();
    private final LongAdder nacksSent          = new LongAdder();

    // ── Increment methods ─────────────────────────────────────────────────────

    public void incMessagesReceived()        { messagesReceived.increment(); }
    public void incMessagesParsed()          { messagesParsed.increment(); }
    public void incParseFailures()           { parseFailures.increment(); }
    public void incBatchesFlushed()          { batchesFlushed.increment(); }
    public void addTotalBatchSize(int n)     { totalBatchSize.add(n); }
    public void incRecordsWritten(int n)     { recordsWritten.add(n); }
    public void incDuplicateMessages()       { duplicateMessages.increment(); }
    public void incTransientFailures()       { transientFailures.increment(); }
    public void incTerminalFailures()        { terminalFailures.increment(); }
    public void incRetries()                 { retries.increment(); }
    public void incDlqPublished()            { dlqPublished.increment(); }
    public void incDlqPublishFailures()      { dlqPublishFailures.increment(); }
    public void incCircuitBreakerSkips()     { cbSkips.increment(); }
    public void incAcksSucceeded()           { acksSucceeded.increment(); }
    public void incNacksSent()               { nacksSent.increment(); }

    // ── Read methods ──────────────────────────────────────────────────────────

    public long getMessagesReceived()   { return messagesReceived.sum(); }
    public long getMessagesParsed()     { return messagesParsed.sum(); }
    public long getParseFailures()      { return parseFailures.sum(); }
    public long getBatchesFlushed()     { return batchesFlushed.sum(); }
    public long getRecordsWritten()     { return recordsWritten.sum(); }
    public long getDuplicateMessages()  { return duplicateMessages.sum(); }
    public long getTransientFailures()  { return transientFailures.sum(); }
    public long getTerminalFailures()   { return terminalFailures.sum(); }
    public long getRetries()            { return retries.sum(); }
    public long getDlqPublished()       { return dlqPublished.sum(); }
    public long getDlqPublishFailures() { return dlqPublishFailures.sum(); }
    public long getCircuitBreakerSkips(){ return cbSkips.sum(); }

    // ── Derived (not failed messages) ─────────────────────────────────────────
    /** Included for backward compatibility with any external consumers of this class. */
    public long getFailedMessages()     { return terminalFailures.sum(); }

    /**
     * Log a one-line summary.  Called by a periodic scheduler.
     */
    public void logSummary() {
        long batches = batchesFlushed.sum();
        long total   = totalBatchSize.sum();
        double avgBatch = (batches > 0) ? (double) total / batches : 0.0;

        log.info("[metrics] recv={} parsed={} parseErr={} | "
               + "batches={} avgBatch={:.1f} | "
               + "written={} dup={} transient={} terminal={} retries={} | "
               + "dlq={} dlqFail={} cbSkip={} | "
               + "acks={} nacks={}",
            messagesReceived.sum(), messagesParsed.sum(), parseFailures.sum(),
            batches, avgBatch,
            recordsWritten.sum(), duplicateMessages.sum(),
            transientFailures.sum(), terminalFailures.sum(), retries.sum(),
            dlqPublished.sum(), dlqPublishFailures.sum(), cbSkips.sum(),
            acksSucceeded.sum(), nacksSent.sum());
    }
}
