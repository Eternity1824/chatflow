package com.chatflow.consumerv3;

/**
 * Result of a single-record write attempt to DynamoDB, including retry
 * classification and attempt count.
 */
public final class PersistenceRecordResult {

    public enum Status {
        /** PutItem succeeded — item was newly created. */
        SUCCESS,
        /** Conditional check failed — item already exists (idempotent duplicate). */
        DUPLICATE,
        /** Write failed. See {@link #getFailureType()} for TRANSIENT vs TERMINAL. */
        FAILURE
    }

    private final String      messageId;
    private final Status      status;
    /**
     * Non-null only when {@link Status#FAILURE}.
     * TRANSIENT = retryable; TERMINAL = give up, send to DLQ.
     */
    private final FailureType failureType;
    private final String      errorMessage;
    /** Number of write attempts made before this result. 0 for SUCCESS/DUPLICATE. */
    private final int         retryCount;

    private PersistenceRecordResult(String messageId, Status status,
                                     FailureType failureType, String errorMessage,
                                     int retryCount) {
        this.messageId    = messageId;
        this.status       = status;
        this.failureType  = failureType;
        this.errorMessage = errorMessage;
        this.retryCount   = retryCount;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static PersistenceRecordResult success(String messageId) {
        return new PersistenceRecordResult(messageId, Status.SUCCESS, null, null, 0);
    }

    public static PersistenceRecordResult duplicate(String messageId) {
        return new PersistenceRecordResult(messageId, Status.DUPLICATE, null, null, 0);
    }

    /** A transient failure that the caller should retry. */
    public static PersistenceRecordResult transientFailure(String messageId, String errorMessage) {
        return new PersistenceRecordResult(messageId, Status.FAILURE,
            FailureType.TRANSIENT, errorMessage, 0);
    }

    /** A terminal failure — retries exhausted or hard error; caller should DLQ. */
    public static PersistenceRecordResult terminalFailure(String messageId, String errorMessage,
                                                           int retryCount) {
        return new PersistenceRecordResult(messageId, Status.FAILURE,
            FailureType.TERMINAL, errorMessage, retryCount);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String      getMessageId()    { return messageId; }
    public Status      getStatus()       { return status; }
    public FailureType getFailureType()  { return failureType; }
    public String      getErrorMessage() { return errorMessage; }
    public int         getRetryCount()   { return retryCount; }

    public boolean isSuccess()          { return status == Status.SUCCESS; }
    public boolean isDuplicate()        { return status == Status.DUPLICATE; }
    public boolean isFailure()          { return status == Status.FAILURE; }
    public boolean isTransientFailure() { return isFailure() && failureType == FailureType.TRANSIENT; }
    public boolean isTerminalFailure()  { return isFailure() && failureType == FailureType.TERMINAL; }

    @Override
    public String toString() {
        return "PersistenceRecordResult{messageId='" + messageId
            + "', status=" + status
            + (failureType  != null ? ", failureType=" + failureType : "")
            + (errorMessage != null ? ", error='" + errorMessage + "'" : "")
            + (retryCount   > 0     ? ", retries=" + retryCount : "") + "}";
    }
}
