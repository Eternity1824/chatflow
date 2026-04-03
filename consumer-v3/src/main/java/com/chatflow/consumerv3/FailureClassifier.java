package com.chatflow.consumerv3;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

/**
 * Classifies DynamoDB exceptions into {@link FailureType#TRANSIENT} or
 * {@link FailureType#TERMINAL}.
 *
 * <p>Rules (in precedence order):
 * <ol>
 *   <li>{@link ConditionalCheckFailedException} is never routed here —
 *       it is caught by {@link PersistenceWriter} and returned as DUPLICATE
 *       before this classifier is invoked.</li>
 *   <li>{@link ProvisionedThroughputExceededException},
 *       {@link RequestLimitExceededException},
 *       {@link TransactionConflictException} → TRANSIENT</li>
 *   <li>{@link SdkClientException} (network timeout, connection refused,
 *       interrupted I/O) → TRANSIENT</li>
 *   <li>Any other {@link DynamoDbException} with HTTP 5xx or
 *       {@code isThrottlingException()==true} → TRANSIENT</li>
 *   <li>Everything else → TERMINAL</li>
 * </ol>
 *
 * Thread-safe: stateless utility class.
 */
public final class FailureClassifier {

    private FailureClassifier() {}

    /**
     * Classify {@code e} as TRANSIENT or TERMINAL.
     * The caller is responsible for never passing a
     * {@link ConditionalCheckFailedException} here.
     */
    public static FailureType classify(Exception e) {
        // Explicit throttle / capacity exceptions
        if (e instanceof ProvisionedThroughputExceededException
                || e instanceof RequestLimitExceededException
                || e instanceof TransactionConflictException) {
            return FailureType.TRANSIENT;
        }

        // Network-level SDK failures (timeout, connection refused, I/O error)
        if (e instanceof SdkClientException) {
            return FailureType.TRANSIENT;
        }

        // Generic DynamoDB service exception — check HTTP status and throttle flag
        if (e instanceof DynamoDbException dde) {
            if (dde.isThrottlingException()) {
                return FailureType.TRANSIENT;
            }
            int status = dde.statusCode();
            if (status >= 500 && status < 600) {
                return FailureType.TRANSIENT;
            }
        }

        // Anything else (4xx, permission denied, validation error, etc.) is terminal
        return FailureType.TERMINAL;
    }
}
