package com.chatflow.consumerv3;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for consumer-v3.
 *
 * <h3>Pipeline</h3>
 * <pre>
 *   RabbitMQ delivery callback  (RabbitMQ client thread)
 *     → QueueEnvelope → BatchAccumulator.add()
 *     → if batch full: submit drainAndFlush("batch-full") to vtExecutor
 *
 *   ScheduledExecutorService  (v3-flush-scheduler thread)
 *     → every flushIntervalMs: if accumulator.isReady() submit drainAndFlush("timer")
 *
 *   vtExecutor  (virtual threads — blocks/parks on DynamoDB I/O and semaphore)
 *     → check CircuitBreaker — if OPEN: skip, messages stay unacked, return
 *     → accumulator.drain()
 *     → CanonicalMessageMapper.fromEnvelope() per envelope
 *     → PersistenceWriter.writeBatch()  (internal retry loop via RetryPolicy)
 *     → per-result ack/nack/dlq
 * </pre>
 *
 * <h3>ACK invariant</h3>
 * A RabbitMQ delivery is acked ONLY when:
 * <ol>
 *   <li>The record is confirmed written to {@code messages_by_id} (SUCCESS or DUPLICATE), or</li>
 *   <li>The record is confirmed written to the SQS DLQ (TERMINAL or PARSE_ERROR).</li>
 * </ol>
 * If SQS publish fails, the delivery is {@code basicNack(requeue=true)} —
 * never silently dropped.
 *
 * <h3>Circuit breaker</h3>
 * Checked before each drain.  When OPEN, the flush is aborted without
 * draining — messages remain in the accumulator (unacked in RabbitMQ) until
 * the breaker resets to CLOSED.  RabbitMQ's {@code prefetch} limit provides
 * natural back-pressure.
 *
 * <h3>Thread safety</h3>
 * The RabbitMQ Java client's {@link Channel} (ChannelN) synchronises all
 * operations internally, making {@code basicAck}/{@code basicNack} calls from
 * virtual threads safe.
 */
public class ConsumerV3App {

    private static final Logger log = LogManager.getLogger(ConsumerV3App.class);

    public static void main(String[] args) throws Exception {
        ConsumerV3Config config = ConsumerV3Config.fromEnv();
        log.info("consumer-v3 starting: {}", config);

        // Fail fast: DLQ is required to guarantee no silent data loss on terminal failures.
        // Without a DLQ, terminal-failure messages would be acked and dropped.
        if (config.sqsDlqUrl.isBlank()) {
            log.fatal("CHATFLOW_V3_SQS_DLQ_URL is not set. "
                + "consumer-v3 requires a DLQ URL to avoid silent message loss. "
                + "Set the env var to the SQS DLQ URL (from Terraform output: sqs_dlq_url) and restart.");
            System.exit(1);
        }

        // ── Build shared components ───────────────────────────────────────────
        ConsumerMetrics        metrics     = new ConsumerMetrics();
        CanonicalMessageMapper mapper      = new CanonicalMessageMapper();
        BatchAccumulator       accumulator = new BatchAccumulator(config.batchSize, config.flushIntervalMs);
        RetryPolicy            retryPolicy = new RetryPolicy(config.retryBaseMs, config.retryMaxMs, config.maxRetries);
        CircuitBreaker         breaker     = new CircuitBreaker(config.cbEnabled, config.cbFailureThreshold, config.cbOpenDurationMs);

        DynamoDbClient dynamoClient = DynamoDbClient.builder()
            .region(Region.of(config.dynamoRegion))
            .build();

        SqsClient sqsClient = SqsClient.builder()
            .region(Region.of(config.dynamoRegion))
            .build();

        DlqPublisher dlqPublisher = new DlqPublisher(sqsClient, config.sqsDlqUrl);

        PersistenceWriter writer = new PersistenceWriter(
            dynamoClient, config.dynamoTableCanonical,
            config.semaphorePermits, retryPolicy, metrics);

        // Virtual-thread executor — one task = one virtual thread.
        // Parks (not blocks) during DynamoDB I/O and semaphore waits.
        ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // ── RabbitMQ topology + connection ────────────────────────────────────
        ConnectionFactory factory = buildConnectionFactory(config);
        Connection connection = factory.newConnection("chatflow-consumer-v3");
        Channel    channel    = connection.createChannel();
        channel.basicQos(config.rabbitPrefetch);
        declareTopology(channel, config);

        List<String> queues = new ArrayList<>();
        for (int roomId = config.roomStart; roomId <= config.roomEnd; roomId++) {
            queues.add(config.queueNameForRoom(roomId));
        }
        log.info("Subscribing to {} room queues (room {}-{})",
            queues.size(), config.roomStart, config.roomEnd);

        // ── basicConsume for every assigned queue ─────────────────────────────
        for (String queueName : queues) {
            channel.basicConsume(
                queueName, /*autoAck=*/ false,
                (consumerTag, delivery) -> {
                    metrics.incMessagesReceived();
                    QueueEnvelope env = new QueueEnvelope(
                        delivery.getBody(), delivery.getEnvelope(),
                        delivery.getProperties(), queueName);

                    boolean full = accumulator.add(env);
                    if (full) {
                        vtExecutor.submit(() ->
                            drainAndFlush("batch-full", channel, accumulator,
                                mapper, writer, dlqPublisher, breaker, metrics));
                    }
                },
                cancelTag -> log.warn("Consumer cancelled for queue={}", queueName)
            );
        }

        // ── Periodic flush timer ──────────────────────────────────────────────
        ScheduledExecutorService flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "v3-flush-scheduler");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(() -> {
            if (accumulator.isReady()) {
                vtExecutor.submit(() ->
                    drainAndFlush("timer", channel, accumulator,
                        mapper, writer, dlqPublisher, breaker, metrics));
            }
        }, config.flushIntervalMs, config.flushIntervalMs, TimeUnit.MILLISECONDS);

        // ── Metrics reporter ──────────────────────────────────────────────────
        ScheduledExecutorService metricsReporter = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "v3-metrics-reporter");
            t.setDaemon(true);
            return t;
        });
        metricsReporter.scheduleAtFixedRate(
            metrics::logSummary,
            config.metricsLogIntervalMs, config.metricsLogIntervalMs, TimeUnit.MILLISECONDS);

        log.info("consumer-v3 running — waiting for messages. CircuitBreaker: {}", breaker);

        // ── Graceful shutdown ─────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown: stopping schedulers...");
            flushScheduler.shutdown();
            metricsReporter.shutdown();

            // Final drain (bypass CB on shutdown to avoid leaving messages unacked)
            List<QueueEnvelope> remaining = accumulator.drain();
            if (!remaining.isEmpty()) {
                log.info("Shutdown: processing {} remaining messages", remaining.size());
                vtExecutor.submit(() ->
                    processBatch(remaining, channel, mapper, writer, dlqPublisher, breaker, metrics));
            }

            vtExecutor.shutdown();
            try {
                if (!vtExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    log.warn("vtExecutor did not terminate in 15s, forcing");
                    vtExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            try { channel.close();    } catch (Exception ignored) {}
            try { connection.close(); } catch (Exception ignored) {}
            writer.close();
            dlqPublisher.close();
            metrics.logSummary();
            log.info("consumer-v3 shutdown complete.");
        }, "v3-shutdown"));

        connection.addBlockedListener(
            reason -> log.warn("RabbitMQ connection blocked: {}", reason),
            () -> log.info("RabbitMQ connection unblocked"));

        Thread.currentThread().join();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drain + flush
    // ─────────────────────────────────────────────────────────────────────────

    private static void drainAndFlush(
            String reason, Channel channel, BatchAccumulator accumulator,
            CanonicalMessageMapper mapper, PersistenceWriter writer,
            DlqPublisher dlqPublisher, CircuitBreaker breaker, ConsumerMetrics metrics) {

        // ── Circuit breaker check (before draining) ───────────────────────────
        // If OPEN: do NOT drain — messages stay in accumulator (still unacked
        // in RabbitMQ), applying natural back-pressure via the prefetch limit.
        if (breaker.isOpen()) {
            log.warn("CircuitBreaker OPEN — skipping flush('{}'), {} msgs held in accumulator",
                reason, accumulator.size());
            metrics.incCircuitBreakerSkips();
            return;
        }

        List<QueueEnvelope> batch = accumulator.drain();
        if (batch.isEmpty()) return;

        log.debug("Flush('{}') batch.size={}", reason, batch.size());
        metrics.incBatchesFlushed();
        metrics.addTotalBatchSize(batch.size());

        processBatch(batch, channel, mapper, writer, dlqPublisher, breaker, metrics);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Process a drained batch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full pipeline for one batch:
     * <ol>
     *   <li>Map envelopes → canonical records (parse failures → DLQ immediately)</li>
     *   <li>Write records to DynamoDB (PersistenceWriter handles internal retries)</li>
     *   <li>Update circuit breaker based on batch outcome</li>
     *   <li>Ack/nack each delivery per result</li>
     * </ol>
     */
    private static void processBatch(
            List<QueueEnvelope> batch, Channel channel,
            CanonicalMessageMapper mapper, PersistenceWriter writer,
            DlqPublisher dlqPublisher, CircuitBreaker breaker, ConsumerMetrics metrics) {

        // ── Phase 1: map → records ────────────────────────────────────────────
        List<CanonicalMessageRecord> records    = new ArrayList<>(batch.size());
        Map<String, QueueEnvelope>   idToEnv    = new HashMap<>(batch.size() * 2);

        for (QueueEnvelope env : batch) {
            try {
                CanonicalMessageRecord record = mapper.fromEnvelope(env);
                records.add(record);
                idToEnv.put(record.getMessageId(), env);
                metrics.incMessagesParsed();
                log.debug("Parsed messageId={} roomId={}", record.getMessageId(), record.getRoomId());
            } catch (Exception e) {
                metrics.incParseFailures();
                log.error("Parse failure deliveryTag={} queue={}: {}",
                    env.getDeliveryTag(), env.getQueueName(), e.getMessage());

                // Parse errors are non-retryable — send straight to DLQ
                DlqPublishResult dlqResult =
                    dlqPublisher.publish(env, null, FailureType.PARSE_ERROR, e.getMessage(), 0);
                handleDlqResult(channel, env, dlqResult, "parse-error", metrics);
            }
        }

        if (records.isEmpty()) return;

        // ── Phase 2: write to DynamoDB (retries handled inside PersistenceWriter) ──
        PersistenceBatchResult result = writer.writeBatch(records);
        log.info("Batch write: {}", result);

        // ── Phase 3: update circuit breaker ──────────────────────────────────
        if (result.hasTerminalFailures()) {
            breaker.recordFailure();
            log.warn("CircuitBreaker.recordFailure(): terminalFailures={} in this batch",
                result.getTerminalFailureCount());
        } else {
            breaker.recordSuccess();
        }

        // ── Phase 4: ack / nack per result ────────────────────────────────────
        for (PersistenceRecordResult rr : result.getResults()) {
            QueueEnvelope env = idToEnv.get(rr.getMessageId());
            if (env == null) {
                log.error("BUG: no envelope for messageId={}", rr.getMessageId());
                continue;
            }
            CanonicalMessageRecord rec = findRecord(records, rr.getMessageId());

            switch (rr.getStatus()) {
                case SUCCESS -> {
                    safeAck(channel, env.getDeliveryTag());
                    metrics.incRecordsWritten(1);
                    metrics.incAcksSucceeded();
                    log.debug("SUCCESS messageId={}", rr.getMessageId());
                }
                case DUPLICATE -> {
                    // Already in DynamoDB — idempotent success, safe to ack
                    safeAck(channel, env.getDeliveryTag());
                    metrics.incDuplicateMessages();
                    metrics.incAcksSucceeded();
                    log.debug("DUPLICATE messageId={}, acking", rr.getMessageId());
                }
                case FAILURE -> {
                    // Terminal failure after retries exhausted — route to SQS DLQ
                    DlqPublishResult dlqResult = dlqPublisher.publish(
                        env, rec, rr.getFailureType(),
                        rr.getErrorMessage(), rr.getRetryCount());
                    handleDlqResult(channel, env, dlqResult,
                        "terminal-write(retries=" + rr.getRetryCount() + ")", metrics);
                }
            }
        }
    }

    // ── ACK policy for DLQ outcomes ───────────────────────────────────────────

    /**
     * Ack if DLQ accepted (or is disabled/best-effort); nack-requeue if DLQ failed.
     *
     * <p>When DLQ is DISABLED the message is acked and a warning is logged.
     * This avoids an infinite nack-requeue loop when no DLQ is configured,
     * but means the message is dropped.  Set {@code CHATFLOW_V3_SQS_DLQ_URL}
     * in production to guarantee no data loss.
     */
    private static void handleDlqResult(Channel channel, QueueEnvelope env,
                                         DlqPublishResult dlqResult,
                                         String context, ConsumerMetrics metrics) {
        switch (dlqResult.getStatus()) {
            case SUCCESS -> {
                safeAck(channel, env.getDeliveryTag());
                metrics.incDlqPublished();
                metrics.incAcksSucceeded();
                log.info("DLQ published [{}] deliveryTag={}", context, env.getDeliveryTag());
            }
            case DISABLED -> {
                // No DLQ configured — best-effort ack to avoid infinite requeue
                safeAck(channel, env.getDeliveryTag());
                metrics.incAcksSucceeded();
                log.warn("DLQ not configured [{}] deliveryTag={} — message dropped",
                    context, env.getDeliveryTag());
            }
            case FAILURE -> {
                // SQS publish failed — MUST NOT ack; requeue for next attempt
                safeNack(channel, env.getDeliveryTag(), true);
                metrics.incDlqPublishFailures();
                metrics.incNacksSent();
                log.error("DLQ publish FAILED [{}] deliveryTag={}: {} — nacking requeue",
                    context, env.getDeliveryTag(), dlqResult.getErrorMessage());
            }
        }
    }

    // ── Channel helpers ───────────────────────────────────────────────────────

    private static void safeAck(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, /*multiple=*/ false);
        } catch (IOException e) {
            log.warn("basicAck failed for deliveryTag={}: {}", deliveryTag, e.getMessage());
        }
    }

    private static void safeNack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, /*multiple=*/ false, requeue);
        } catch (IOException e) {
            log.warn("basicNack failed for deliveryTag={}: {}", deliveryTag, e.getMessage());
        }
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static CanonicalMessageRecord findRecord(List<CanonicalMessageRecord> records,
                                                      String messageId) {
        for (CanonicalMessageRecord r : records) {
            if (messageId.equals(r.getMessageId())) return r;
        }
        return null;
    }

    // ── RabbitMQ setup helpers ────────────────────────────────────────────────

    private static ConnectionFactory buildConnectionFactory(ConsumerV3Config config) {
        ConnectionFactory f = new ConnectionFactory();
        f.setHost(config.rabbitHost);
        f.setPort(config.rabbitPort);
        f.setUsername(config.rabbitUsername);
        f.setPassword(config.rabbitPassword);
        f.setVirtualHost(config.rabbitVhost);
        f.setAutomaticRecoveryEnabled(true);
        f.setTopologyRecoveryEnabled(true);
        return f;
    }

    private static void declareTopology(Channel channel, ConsumerV3Config config)
            throws IOException {
        channel.exchangeDeclare(config.rabbitExchange, BuiltinExchangeType.TOPIC, true);

        Map<String, Object> queueArgs = new HashMap<>();
        if (config.queueMessageTtlMs > 0) queueArgs.put("x-message-ttl",  config.queueMessageTtlMs);
        if (config.queueMaxLength    > 0) queueArgs.put("x-max-length",    config.queueMaxLength);
        Map<String, Object> args = queueArgs.isEmpty() ? null : queueArgs;

        for (int roomId = config.roomStart; roomId <= config.roomEnd; roomId++) {
            String queueName = config.queueNameForRoom(roomId);
            channel.queueDeclare(queueName, true, false, false, args);
            channel.queueBind(queueName, config.rabbitExchange, queueName);
        }
        log.info("Topology declared: exchange={}, rooms={}-{}",
            config.rabbitExchange, config.roomStart, config.roomEnd);
    }
}
