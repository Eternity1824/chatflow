package com.chatflow.consumerv3;

/**
 * Classification of write / parse failures in the consumer-v3 pipeline.
 *
 * <table>
 * <tr><th>Type</th><th>Examples</th><th>Action</th></tr>
 * <tr><td>TRANSIENT</td>
 *     <td>DynamoDB throttling, AWS 5xx, network timeout</td>
 *     <td>Retry with exponential backoff; escalate to TERMINAL on exhaustion</td></tr>
 * <tr><td>TERMINAL</td>
 *     <td>Retries exhausted, permission denied, bad table config</td>
 *     <td>Send to SQS DLQ then ack RabbitMQ</td></tr>
 * <tr><td>PARSE_ERROR</td>
 *     <td>Invalid Protobuf bytes, blank messageId/roomId</td>
 *     <td>Send to SQS DLQ immediately (no retry); then ack RabbitMQ</td></tr>
 * </table>
 */
public enum FailureType {
    /**
     * Failure is likely temporary — retry with exponential back-off.
     * DynamoDB throttling, AWS 5xx, SDK timeout, transient network error.
     */
    TRANSIENT,

    /**
     * Failure is permanent for this message — send to DLQ.
     * Includes both hard AWS errors (4xx, permission denied) and cases
     * where all TRANSIENT retry attempts have been exhausted.
     */
    TERMINAL,

    /**
     * Message cannot be deserialized or fails field validation.
     * No amount of retrying will fix a corrupt/invalid payload — go
     * straight to DLQ without any retry.
     */
    PARSE_ERROR
}
