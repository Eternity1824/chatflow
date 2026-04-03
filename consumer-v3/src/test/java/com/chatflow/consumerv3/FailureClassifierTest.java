package com.chatflow.consumerv3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureClassifierTest {

    @Test
    void provisionedThroughputExceeded_isTransient() {
        Exception e = ProvisionedThroughputExceededException.builder()
            .message("throughput exceeded").build();
        assertEquals(FailureType.TRANSIENT, FailureClassifier.classify(e));
    }

    @Test
    void transactionConflict_isTransient() {
        Exception e = TransactionConflictException.builder()
            .message("conflict").build();
        assertEquals(FailureType.TRANSIENT, FailureClassifier.classify(e));
    }

    @Test
    void sdkClientException_network_isTransient() {
        Exception e = SdkClientException.builder()
            .message("Connection refused").build();
        assertEquals(FailureType.TRANSIENT, FailureClassifier.classify(e));
    }

    @Test
    void dynamoDbException_5xx_isTransient() {
        DynamoDbException e = (DynamoDbException) DynamoDbException.builder()
            .statusCode(503)
            .awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("ServiceUnavailable")
                .errorMessage("service down")
                .build())
            .build();
        assertEquals(FailureType.TRANSIENT, FailureClassifier.classify(e));
    }

    @Test
    void dynamoDbException_4xx_isTerminal() {
        DynamoDbException e = (DynamoDbException) DynamoDbException.builder()
            .statusCode(400)
            .awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("ValidationException")
                .errorMessage("bad request")
                .build())
            .build();
        assertEquals(FailureType.TERMINAL, FailureClassifier.classify(e));
    }

    @Test
    void genericRuntimeException_isTerminal() {
        assertEquals(FailureType.TERMINAL,
            FailureClassifier.classify(new RuntimeException("unknown")));
    }
}
