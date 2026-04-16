# Assignment 3 — Final Implementation Architecture

## Overview

Assignment 3 adds durable message persistence, CDC-based projection fan-out,
split async analytics (`Lambda A -> SQS -> Lambda B -> Redis`), and a
query/metrics API on top of the Assignment 2 pipeline.

```
Client → ALB → server-v2 → RabbitMQ
                                ↓
                        consumer-v3
                          BatchAccumulator
                                ↓ conditional PutItem
                        DynamoDB: messages_by_id
                                ↓ DynamoDB Streams (NEW_IMAGE)
                        Lambda A: CDC projector
                          ├── room_messages   (PutItem, idempotent)
                          ├── user_messages   (PutItem, idempotent)
                          ├── user_rooms      (conditional UpdateItem)
                          └── SQS analytics queue
                                ↓
                        Lambda B: Redis analytics
                          └── Redis           (Lua EVAL: dedupe + analytics writes + health markers)

Client ← HTTP GET /api/metrics/report ← server-v2 ApiHandler
                                            ├── QueryService (DynamoDB reads)
                                            ├── AnalyticsService (Redis reads)
                                            └── ProjectionHealthService (Redis reads)
```

---

## Component Details

### 1. consumer-v3 — Canonical Write

**Module:** `consumer-v3/`

- Consumes from RabbitMQ (AMQP 0-9-1), prefetch configured via `CHATFLOW_V3_RABBIT_PREFETCH`
- `BatchAccumulator`: collects messages up to `CHATFLOW_V3_BATCH_SIZE` or `CHATFLOW_V3_FLUSH_INTERVAL_MS`
- `PersistenceWriter`: conditional `PutItem` to `messages_by_id` using `attribute_not_exists(messageId)`
- ACK sent **after** successful write (or idempotent duplicate); NACK with retry on transient failure
- Exponential backoff: `CHATFLOW_V3_RETRY_BASE_MS` to `CHATFLOW_V3_RETRY_MAX_MS`, max `CHATFLOW_V3_MAX_RETRIES` retries
- Circuit breaker trips after repeated DynamoDB failures → fast-fail + SQS DLQ send
- Semaphore (`CHATFLOW_V3_SEMAPHORE_PERMITS`) limits concurrent DynamoDB requests

### 2. Lambda A — Projection Fan-out

**Module:** `projection-lambda/`

- Triggered by DynamoDB Streams on `messages_by_id`
- Returns `StreamsEventResponse` with `batchItemFailures` — only failed records are retried
- Per INSERT record:
  1. Parse `ProjectionEvent` from `NEW_IMAGE`
  2. `ProjectionWriter.write()`: PutItem to `room_messages` + `user_messages`, UpdateItem to `user_rooms`
  3. Publish `AnalyticsEvent` to the SQS analytics queue
- MODIFY / REMOVE events are silently skipped (not failures)
- Terraform: `function_response_types = ["ReportBatchItemFailures"]`

### 3. Lambda B — Redis Analytics

**Module:** `projection-lambda/`

- Triggered by the SQS analytics queue populated by Lambda A
- Parses `AnalyticsEvent` JSON messages from SQS
- Runs Redis batch analytics via Lua so dedupe + counters + health markers
  are updated atomically
- Returns `SQSBatchResponse` with `batchItemFailures`, so only failed SQS
  messages are retried
- Keeps Redis-side failures isolated from the DynamoDB projection writer

### 4. server-v2 — Query & Metrics API

**Module:** `server-v2/`

New components (Phase 5):

| Class | Responsibility |
|-------|---------------|
| `ServerV2PersistenceConfig` | Reads env vars; `isDynamoEnabled()` / `isRedisEnabled()` guards |
| `QueryService` | DynamoDB queries: room/user messages (day-bucket expansion), user rooms |
| `AnalyticsService` | Redis: active users (SUNIONSTORE), top users/rooms (ZUNIONSTORE), msg rates |
| `ProjectionHealthService` | Reads projection health markers; computes lagMs and isConsistent |
| `ApiHandler` | Netty `@Sharable` handler; routes `/api/*`, dispatches to ExecutorService |

Endpoint summary:

```
GET /api/query/rooms/{roomId}/messages?start=&end=
GET /api/query/users/{userId}/messages?start=&end=
GET /api/query/users/{userId}/rooms
GET /api/query/active-users?start=&end=
GET /api/analytics/summary?start=&end=&topN=
GET /api/metrics/report?start=&end=&topN=
```

Default time window: last 15 minutes; default topN: 10.

### 5. client-part2 — Post-test Metrics Fetch

**Module:** `client-part2/`

- `MetricsReportClient`: derives `http://host:port` from `ws://host:port/chat`
- Called automatically after `writeSummaryCsv()`
- Logs full JSON report + human-readable summary (top rooms/users, lag, consistency)
- Controlled by `CHATFLOW_REPORT_ENABLED` env var (default true)

### 6. Error Recovery

| Failure type | Recovery mechanism |
|-------------|-------------------|
| Transient DynamoDB write | Exponential backoff retry (consumer-v3) |
| Permanent DynamoDB failure | SQS DLQ (consumer-v3 circuit breaker) |
| Lambda A stream parse / projection error | `batchItemFailures` → DynamoDB Stream retries that record |
| Lambda A SQS publish error | `batchItemFailures` → DynamoDB Stream retries that record |
| Lambda B Redis / parse error | `batchItemFailures` → SQS retries that message |
| Duplicate Lambda invocation | idempotent projection writes + Redis dedupe marker |

### 7. Idempotency

- `messages_by_id`: `ConditionExpression: attribute_not_exists(messageId)`
- `room_messages` / `user_messages`: `ConditionExpression: attribute_not_exists(sk)`
- `user_rooms`: conditional UpdateItem advances `lastActivityTs` only forward
- Redis analytics: `SET NX EX` dedupe marker — if already set, Lua script returns 0 and skips all counters

---

## Key Environment Variables

See `config/consumer-v3-local.env.example` for consumer-v3 vars.
See `deployment/consumer-v3-env-example.sh` for production vars.

server-v2 persistence vars:
```
CHATFLOW_V3_DYNAMO_REGION
CHATFLOW_V3_DYNAMO_TABLE_ROOM_MESSAGES
CHATFLOW_V3_DYNAMO_TABLE_USER_MESSAGES
CHATFLOW_V3_DYNAMO_TABLE_USER_ROOMS
CHATFLOW_V3_REDIS_ENDPOINT              # host:port, used by server-v2 only
CHATFLOW_V3_PROJECTION_HEALTH_THRESHOLD_MS  # default 5000
```
