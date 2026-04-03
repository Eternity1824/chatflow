package com.chatflow.consumerv3;

import com.rabbitmq.client.Channel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Coordinates RabbitMQ acknowledgements after a batch has been durably
 * persisted to DynamoDB.
 *
 * <p>Design intent:
 * <ul>
 *   <li>Only ack a message after its {@link CanonicalMessageRecord} is
 *       confirmed written — never before.</li>
 *   <li>On write failure the message is nacked (requeued=false) and the
 *       {@link DlqPublisher} handles it separately via SQS.</li>
 *   <li>Uses {@code basicAck(deliveryTag, multiple=false)} per message to
 *       avoid inadvertently acking later deliveries.</li>
 * </ul>
 *
 * TODO (Phase 2): implement actual ack/nack path and integrate with
 * the virtual-thread persistence pipeline.
 */
public class AckCoordinator {

    private static final Logger log = LogManager.getLogger(AckCoordinator.class);

    private final Channel channel;

    public AckCoordinator(Channel channel) {
        this.channel = channel;
    }

    /**
     * Ack all envelopes in the supplied batch (called after successful write).
     *
     * TODO (Phase 2): implement
     */
    public void ackBatch(List<QueueEnvelope> envelopes) {
        for (QueueEnvelope env : envelopes) {
            try {
                channel.basicAck(env.getDeliveryTag(), false);
            } catch (IOException e) {
                log.error("Failed to ack delivery tag {}: {}", env.getDeliveryTag(), e.getMessage());
                // TODO: handle channel-level errors (reconnect / re-queue logic)
            }
        }
    }

    /**
     * Nack a single envelope without requeue (message goes to RabbitMQ DLX or
     * is discarded; SQS DLQ is written separately by {@link DlqPublisher}).
     *
     * TODO (Phase 2): implement
     */
    public void nack(QueueEnvelope envelope) {
        try {
            channel.basicNack(envelope.getDeliveryTag(), false, false);
        } catch (IOException e) {
            log.error("Failed to nack delivery tag {}: {}", envelope.getDeliveryTag(), e.getMessage());
        }
    }
}
