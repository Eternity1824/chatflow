package com.chatflow.consumerv3;

/**
 * Result of a single {@link DlqPublisher#publish} call.
 *
 * <ul>
 *   <li>{@code SUCCESS}  — SQS accepted the message.  Caller may ack RabbitMQ.</li>
 *   <li>{@code FAILURE}  — SQS rejected or unreachable.  Caller must NOT ack;
 *                          use {@code basicNack(requeue=true)} instead.</li>
 *   <li>{@code DISABLED} — DLQ URL not configured.  Caller decides policy
 *                          (current policy: ack + log warning, treat as best-effort drop).</li>
 * </ul>
 */
public final class DlqPublishResult {

    public enum Status { SUCCESS, FAILURE, DISABLED }

    private final Status status;
    private final String errorMessage;

    private DlqPublishResult(Status status, String errorMessage) {
        this.status       = status;
        this.errorMessage = errorMessage;
    }

    public static DlqPublishResult success()                        { return new DlqPublishResult(Status.SUCCESS,   null); }
    public static DlqPublishResult failure(String errorMessage)     { return new DlqPublishResult(Status.FAILURE,  errorMessage); }
    public static DlqPublishResult disabled()                       { return new DlqPublishResult(Status.DISABLED, null); }

    public Status  getStatus()       { return status; }
    public String  getErrorMessage() { return errorMessage; }

    public boolean isSuccess()  { return status == Status.SUCCESS; }
    public boolean isFailure()  { return status == Status.FAILURE; }
    public boolean isDisabled() { return status == Status.DISABLED; }

    @Override
    public String toString() {
        return "DlqPublishResult{status=" + status
            + (errorMessage != null ? ", error='" + errorMessage + "'" : "") + "}";
    }
}
