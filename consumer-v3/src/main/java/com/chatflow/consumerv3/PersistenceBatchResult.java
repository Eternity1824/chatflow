package com.chatflow.consumerv3;

import java.util.List;

/**
 * Aggregated result of a single batch write to DynamoDB.
 */
public final class PersistenceBatchResult {

    private final List<PersistenceRecordResult> results;
    private final int successCount;
    private final int duplicateCount;
    private final int transientFailureCount;
    private final int terminalFailureCount;

    public PersistenceBatchResult(List<PersistenceRecordResult> results) {
        this.results = List.copyOf(results);
        int s = 0, d = 0, tr = 0, te = 0;
        for (PersistenceRecordResult r : results) {
            switch (r.getStatus()) {
                case SUCCESS   -> s++;
                case DUPLICATE -> d++;
                case FAILURE   -> {
                    if (r.isTransientFailure()) tr++;
                    else                        te++;
                }
            }
        }
        this.successCount          = s;
        this.duplicateCount        = d;
        this.transientFailureCount = tr;
        this.terminalFailureCount  = te;
    }

    public List<PersistenceRecordResult> getResults()             { return results; }
    public int  getSuccessCount()                                  { return successCount; }
    public int  getDuplicateCount()                                { return duplicateCount; }
    public int  getTransientFailureCount()                         { return transientFailureCount; }
    public int  getTerminalFailureCount()                          { return terminalFailureCount; }
    public int  getFailureCount()                                  { return transientFailureCount + terminalFailureCount; }
    public int  getTotalCount()                                    { return results.size(); }

    /** True if any record ended in a terminal (DLQ-bound) failure. */
    public boolean hasTerminalFailures()  { return terminalFailureCount  > 0; }
    /** True if any record ended in a transient (still-retryable) failure. */
    public boolean hasTransientFailures() { return transientFailureCount > 0; }
    public boolean hasFailures()          { return getFailureCount() > 0; }

    @Override
    public String toString() {
        return "PersistenceBatchResult{total=" + results.size()
            + ", success=" + successCount
            + ", dup=" + duplicateCount
            + ", transient=" + transientFailureCount
            + ", terminal=" + terminalFailureCount + "}";
    }
}
