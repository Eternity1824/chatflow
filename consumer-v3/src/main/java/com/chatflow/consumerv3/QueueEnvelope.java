package com.chatflow.consumerv3;

import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.AMQP;

/**
 * Wraps a single raw RabbitMQ delivery together with its AMQP metadata.
 * The {@link #payload} field holds the raw bytes of the queue message
 * (Protobuf-encoded {@code QueueChatMessage}).
 *
 * <p>This object is handed off from the AMQP delivery callback into the
 * {@link BatchAccumulator}. The {@link AckCoordinator} uses the
 * {@link #deliveryTag} to ack or nack the broker once the batch has been
 * durably persisted (or sent to the DLQ after exhausted retries).
 */
public class QueueEnvelope {

    /** Raw wire bytes received from RabbitMQ (Protobuf-encoded). */
    private final byte[] payload;

    /** AMQP delivery tag — used by {@link AckCoordinator} to ack/nack. */
    private final long deliveryTag;

    /** Name of the queue this message was delivered from. */
    private final String queueName;

    /** AMQP routing key. */
    private final String routingKey;

    /** Number of times this message has been attempted (starts at 1). */
    private int attemptCount;

    /** Time (nanos) when this envelope was created — for latency tracking. */
    private final long receivedAtNanos;

    public QueueEnvelope(byte[] payload, Envelope envelope, AMQP.BasicProperties properties, String queueName) {
        this.payload        = payload;
        this.deliveryTag    = envelope.getDeliveryTag();
        this.routingKey     = envelope.getRoutingKey();
        this.queueName      = queueName;
        this.attemptCount   = 1;
        this.receivedAtNanos = System.nanoTime();
    }

    // Lightweight constructor for tests / manual construction
    public QueueEnvelope(byte[] payload, long deliveryTag, String queueName, String routingKey) {
        this.payload        = payload;
        this.deliveryTag    = deliveryTag;
        this.queueName      = queueName;
        this.routingKey     = routingKey;
        this.attemptCount   = 1;
        this.receivedAtNanos = System.nanoTime();
    }

    public byte[]  getPayload()         { return payload; }
    public long    getDeliveryTag()     { return deliveryTag; }
    public String  getQueueName()       { return queueName; }
    public String  getRoutingKey()      { return routingKey; }
    public int     getAttemptCount()    { return attemptCount; }
    public long    getReceivedAtNanos() { return receivedAtNanos; }

    public void incrementAttempt() { this.attemptCount++; }

    @Override
    public String toString() {
        return "QueueEnvelope{deliveryTag=" + deliveryTag
            + ", queue='" + queueName + "', attempt=" + attemptCount + "}";
    }
}
