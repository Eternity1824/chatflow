# Assignment 3 — Runbook

This document covers how to start the full Assignment 3 stack, run load tests,
and verify persistence health.

---

## 1. Startup Order

Start components in this order.  Each step depends on the previous one being ready.

### Step 1 — Terraform infrastructure

```bash
cd deployment/terraform
terraform apply -var="enable_persistence=true" \
                -var="lambda_cdc_jar_s3_bucket=<your-bucket>" \
                -var="enable_elasticache=true" \
                -var="elasticache_subnet_ids=[\"subnet-xxx\"]"
```

If your AWS lab account cannot create IAM roles or instance profiles, supply the
pre-created lab identities instead of letting Terraform create new ones:

```bash
terraform apply -var="enable_persistence=true" \
                -var="existing_server_instance_profile_name=LabInstanceProfile" \
                -var="existing_consumer_instance_profile_name=LabInstanceProfile" \
                -var="existing_lambda_role_arn=arn:aws:iam::<account-id>:role/LabRole"
```

Note the outputs:
- `dynamo_canonical_table_name`
- `dynamo_stream_arn`
- `sqs_dlq_url`
- `redis_endpoint`
- `lambda_function_name`                  # Lambda A
- `lambda_analytics_function_name`        # Lambda B
- `sqs_analytics_queue_url`

### Step 2 — Build and upload the shared Lambda JAR

```bash
./gradlew :projection-lambda:shadowJar
aws s3 cp projection-lambda/build/libs/projection-lambda-all.jar \
    s3://<your-bucket>/chatflow/projection-lambda-all.jar
```

Terraform uses the same JAR for both Lambda handlers:

- Lambda A: `com.chatflow.cdc.CdcProjectorHandler::handleRequest`
- Lambda B: `com.chatflow.cdc.RedisAnalyticsHandler::handleRequest`

Lambda A is wired to the DynamoDB stream automatically.
Lambda B is wired to the SQS analytics queue automatically.

### Step 3 — Start consumer-v3

```bash
# Set env vars (see config/consumer-v3-local.env.example for all options)
export CHATFLOW_V3_RABBIT_HOST=<rabbitmq-ip>
export CHATFLOW_V3_DYNAMO_REGION=us-west-2
export CHATFLOW_V3_DYNAMO_TABLE_CANONICAL=chatflow-messages-by-id
export CHATFLOW_V3_SQS_DLQ_URL=<sqs-dlq-url-from-terraform>
export CHATFLOW_V3_BATCH_SIZE=500
export CHATFLOW_V3_FLUSH_INTERVAL_MS=500

java -jar consumer-v3/build/libs/consumer-v3-all.jar
```

Verify startup log shows:
```
BatchAccumulator started (batchSize=500, flushIntervalMs=500)
PersistenceWriter connected to DynamoDB table: chatflow-messages-by-id
```

### Step 4 — Start server-v2

```bash
export CHATFLOW_V3_DYNAMO_REGION=us-west-2
export CHATFLOW_V3_DYNAMO_TABLE_ROOM_MESSAGES=chatflow-room-messages
export CHATFLOW_V3_DYNAMO_TABLE_USER_MESSAGES=chatflow-user-messages
export CHATFLOW_V3_DYNAMO_TABLE_USER_ROOMS=chatflow-user-rooms
export CHATFLOW_V3_REDIS_ENDPOINT=<elasticache-host>:6379
export CHATFLOW_V3_PROJECTION_HEALTH_THRESHOLD_MS=5000

java -jar server-v2/build/libs/server-v2-all.jar
```

Smoke-test the API:
```bash
curl http://localhost:8080/api/metrics/report | jq .health
```

### Step 5 — Run client-part2 load test

```bash
java -jar client-part2/build/libs/client-part2-all.jar \
     --config config/assignment3-baseline.yml
```

After the test completes, the client automatically fetches and logs the metrics
report.  Look for:
```
=== Assignment 3 Metrics Report ===
{ "generatedAt": "...", ... }
=== Metrics Summary ===
projectionLagMs=...  isConsistent=...
```

---

## 2. Key Environment Variables Reference

| Variable | Component | Description |
|----------|-----------|-------------|
| `CHATFLOW_V3_RABBIT_HOST` | consumer-v3 | RabbitMQ hostname |
| `CHATFLOW_V3_BATCH_SIZE` | consumer-v3 | DynamoDB write batch size |
| `CHATFLOW_V3_FLUSH_INTERVAL_MS` | consumer-v3 | Max time between flushes |
| `CHATFLOW_V3_SQS_DLQ_URL` | consumer-v3 | DLQ URL for failed writes |
| `CHATFLOW_V3_DYNAMO_REGION` | consumer-v3, server-v2 | AWS region |
| `CHATFLOW_V3_DYNAMO_TABLE_CANONICAL` | consumer-v3 | `messages_by_id` table name |
| `CHATFLOW_V3_DYNAMO_TABLE_ROOM_MESSAGES` | server-v2 | Projection table name |
| `CHATFLOW_V3_DYNAMO_TABLE_USER_MESSAGES` | server-v2 | Projection table name |
| `CHATFLOW_V3_DYNAMO_TABLE_USER_ROOMS` | server-v2 | Projection table name |
| `CHATFLOW_V3_REDIS_ENDPOINT` | server-v2 | `host:port` |
| `CHATFLOW_V3_PROJECTION_HEALTH_THRESHOLD_MS` | server-v2 | Lag threshold for `isConsistent` |
| `CHATFLOW_REPORT_ENABLED` | client-part2 | Set `false` to skip report fetch |
| `CHATFLOW_REPORT_URL` | client-part2 | Override report base URL |

Lambda A env vars (set via Terraform):
- `DYNAMO_REGION`, `DYNAMO_TABLE_ROOM_MESSAGES`, `DYNAMO_TABLE_USER_MESSAGES`,
  `DYNAMO_TABLE_USER_ROOMS`, `SQS_ANALYTICS_QUEUE_URL`

Lambda B env vars (set via Terraform):
- `DYNAMO_REGION`, `REDIS_ENDPOINT`, `REDIS_DEDUPE_EXPIRE_SECONDS`

---

## 3. Manually Fetch `/api/metrics/report`

After a test, pull the report for a specific time window:

```bash
START_MS=1700000000000   # epoch ms from client log
END_MS=1700003600000

curl "http://<server-v2-ip>:8080/api/metrics/report?start=${START_MS}&end=${END_MS}&topN=10" \
  | jq .
```

Fields in the response:

| Field | Description |
|-------|-------------|
| `generatedAt` | ISO-8601 timestamp of report generation |
| `windowStartMs` / `windowEndMs` | Query window |
| `coreQueries.activeUsers.activeUserCount` | Unique users in window |
| `analytics.messagesPerMinute` | Array of `{minuteBucket, count}` |
| `analytics.messagesPerSecond` | Array of `{secondBucket, count}` |
| `analytics.topUsers` | Array of `{id, score}` (descending) |
| `analytics.topRooms` | Array of `{id, score}` (descending) |
| `health.projectionLagMs` | ms since last projected message was ingested |
| `health.isConsistent` | `true` if lag < threshold |
| `health.lastProjectedMessageId` | Last message ID acknowledged by the Redis analytics stage |
| `health.thresholdMs` | Configured consistency threshold |

---

## 4. Check Projection Lag

```bash
# Quick check via metrics API
curl "http://<server-v2-ip>:8080/api/metrics/report" | jq '.health'

# Direct Redis check (must be in VPC or use SSH tunnel)
REDIS_HOST=<endpoint> REDIS_PORT=6379 ./monitoring/collect-redis-metrics.sh
```

`projectionLagMs` < 5 000 and `isConsistent = true` → Lambda has caught up.
If lag is persistently > 30 000 ms → check CloudWatch Lambda `IteratorAge`.

---

## 5. Check SQS DLQ

```bash
# Get queue URL
DLQ_URL=$(aws sqs get-queue-url \
  --queue-name chatflow-consumer-v3-dlq \
  --query 'QueueUrl' --output text)

# Check depth
aws sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names ApproximateNumberOfMessages

# Peek at a message (does not delete)
aws sqs receive-message --queue-url "$DLQ_URL" --max-number-of-messages 1
```

---

## 6. Collect Monitoring Snapshots

Before tearing down infrastructure after a test:

```bash
mkdir -p monitoring/output

# RabbitMQ
RABBIT_HOST=<ip> ./monitoring/collect-rabbitmq-metrics.sh \
  > monitoring/output/rabbitmq-$(date +%Y%m%d-%H%M%S).csv

# AWS (DynamoDB / Lambda / SQS)
NAME_PREFIX=chatflow ./monitoring/collect-aws-persistence-metrics.sh \
  > monitoring/output/aws-$(date +%Y%m%d-%H%M%S).csv

# Redis
REDIS_HOST=<endpoint> ./monitoring/collect-redis-metrics.sh \
  > monitoring/output/redis-$(date +%Y%m%d-%H%M%S).txt
```
