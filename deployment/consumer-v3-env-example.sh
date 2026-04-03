#!/usr/bin/env bash
# Example environment variables for consumer-v3 (Assignment 3)
# Copy and fill in real values before starting the service.
#
# Usage:
#   source deployment/consumer-v3-env-example.sh
#   java -jar consumer-v3/build/libs/consumer-v3-1.0-SNAPSHOT-all.jar

# ── RabbitMQ ──────────────────────────────────────────────────────────────────
export CHATFLOW_V3_RABBIT_HOST="rabbitmq-host"
export CHATFLOW_V3_RABBIT_PORT="5672"
export CHATFLOW_V3_RABBIT_USERNAME="guest"
export CHATFLOW_V3_RABBIT_PASSWORD="guest"
export CHATFLOW_V3_RABBIT_VHOST="/"
export CHATFLOW_V3_RABBIT_EXCHANGE="chat.exchange"
export CHATFLOW_V3_RABBIT_PREFETCH="200"

# ── DynamoDB ──────────────────────────────────────────────────────────────────
export CHATFLOW_V3_DYNAMO_REGION="us-east-1"
export CHATFLOW_V3_DYNAMO_TABLE_CANONICAL="messages_by_id"

# ── SQS DLQ ───────────────────────────────────────────────────────────────────
# Obtain from Terraform output: sqs_dlq_url
export CHATFLOW_V3_SQS_DLQ_URL="https://sqs.us-east-1.amazonaws.com/123456789012/chatflow-consumer-v3-dlq"

# ── Batch / flush ─────────────────────────────────────────────────────────────
export CHATFLOW_V3_BATCH_SIZE="100"
export CHATFLOW_V3_FLUSH_INTERVAL_MS="500"
export CHATFLOW_V3_SEMAPHORE_PERMITS="64"

# ── Retry  (env alias: CHATFLOW_V3_MAX_RETRIES / CHATFLOW_V3_RETRY_BASE_MS / ...) ──
export CHATFLOW_V3_MAX_RETRIES="5"
export CHATFLOW_V3_RETRY_BASE_MS="50"
export CHATFLOW_V3_RETRY_MAX_MS="5000"

# ── Circuit Breaker ───────────────────────────────────────────────────────────
# CHATFLOW_V3_CB_ENABLED: true (default) | false
export CHATFLOW_V3_CB_ENABLED="true"
# Open after N consecutive batches with terminal failures
export CHATFLOW_V3_CB_FAILURE_THRESHOLD="5"
# Stay open for this many ms before auto-resetting to CLOSED
export CHATFLOW_V3_CB_OPEN_MS="30000"

# ── Observability ─────────────────────────────────────────────────────────────
export CHATFLOW_V3_METRICS_PORT="8091"
export CHATFLOW_V3_METRICS_LOG_INTERVAL_MS="30000"
