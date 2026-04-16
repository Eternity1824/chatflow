# ChatFlow

ChatFlow is a distributed WebSocket chat system built for the CS6650 assignments.
It starts with a single Netty echo server, then evolves into a load-balanced,
queue-backed, persistent chat platform with query and analytics APIs.

The current implementation focuses on the Assignment 2 and Assignment 3 design:
clients send room messages over WebSocket, `server-v2` publishes them to
RabbitMQ, consumers persist canonical records to DynamoDB, and CDC/Lambda
projections maintain query tables plus Redis analytics.

```text
client-part2
    |
    | WebSocket /chat?roomId=<room>
    v
ALB / server-v2  --->  RabbitMQ room queues  --->  consumer-v3
    |                                                 |
    | HTTP query and metrics APIs                     v
    |                                           DynamoDB canonical table
    |                                                 |
    v                                                 v
DynamoDB projections <--- projection-lambda <--- DynamoDB Streams
    |
    v
Redis analytics counters
```

## What Is Implemented

- Netty WebSocket servers for the original echo flow and the queue-backed flow.
- RabbitMQ publishing, room queue consumption, and internal fan-out support.
- Protobuf-based shared message models.
- Batched DynamoDB persistence with retry, DLQ, and circuit-breaker logic.
- DynamoDB Streams projection Lambda for room, user, and analytics views.
- Redis-backed analytics for active users, top users, top rooms, and message
  rates.
- Load-test clients, scenario configs, monitoring scripts, and assignment
  reports.

## Repository Layout

| Path | Purpose |
| --- | --- |
| `common/` | Shared protocol models, protobuf generation, validation, and utilities. |
| `server/` | Assignment 1 Netty WebSocket server. |
| `server-v2/` | Queue-backed WebSocket/API server used by later assignments. |
| `consumer/` | Assignment 2 RabbitMQ consumer and broadcast worker. |
| `consumer-v3/` | Assignment 3 persistence consumer for DynamoDB writes and DLQ handling. |
| `projection-lambda/` | DynamoDB Streams and Redis analytics Lambda handlers. |
| `client-part1/` | Earlier load-testing client. |
| `client-part2/` | Config-driven load client with latency, throughput, and metrics outputs. |
| `config/` | Client and consumer scenario configs. |
| `database/` | DynamoDB schema and storage design notes. |
| `deployment/` | Terraform, Dockerfiles, and deployment environment examples. |
| `load-tests/` | Load-test plans, matrices, and report templates. |
| `monitoring/` | Scripts for RabbitMQ, AWS, Redis, and instance-level metrics. |
| `docs/` | Runbooks, assignment reports, requirement references, and this overview. |
| `results/` | Local/generated load-test outputs and screenshots. |

## Main Runtime Components

### `server-v2`

`server-v2` accepts WebSocket traffic at:

```text
ws://<host>:8080/chat?roomId=<room>
```

It validates incoming messages, publishes them to RabbitMQ, tracks local room
sessions, exposes an internal broadcast endpoint, and serves query/metrics APIs:

```text
GET /api/query/rooms/{roomId}/messages?start=<ms>&end=<ms>
GET /api/query/users/{userId}/messages?start=<ms>&end=<ms>
GET /api/query/users/{userId}/rooms
GET /api/query/active-users?start=<ms>&end=<ms>
GET /api/analytics/summary?start=<ms>&end=<ms>&topN=<n>
GET /api/metrics/report?start=<ms>&end=<ms>&topN=<n>
```

### `consumer-v3`

`consumer-v3` consumes room queues, batches messages, writes the canonical
`messages_by_id` table, retries transient failures, and can publish terminal
failures to an SQS DLQ.

Important environment variables include:

```text
CHATFLOW_V3_RABBIT_HOST
CHATFLOW_V3_DYNAMO_REGION
CHATFLOW_V3_DYNAMO_TABLE_CANONICAL
CHATFLOW_V3_SQS_DLQ_URL
CHATFLOW_V3_BATCH_SIZE
CHATFLOW_V3_FLUSH_INTERVAL_MS
```

See `config/consumer-v3-local.env.example` and `deployment/consumer-v3-env-example.sh`
for complete examples.

### `projection-lambda`

The projection Lambda reads DynamoDB Streams from the canonical table and writes
query-optimized projections. It also updates Redis analytics counters used by
the API report endpoints.

## Build And Test

Build everything:

```bash
./gradlew build
```

Run all tests:

```bash
./gradlew test
```

Build selected modules:

```bash
./gradlew :server-v2:build
./gradlew :consumer-v3:build
./gradlew :client-part2:build
./gradlew :projection-lambda:build
```

Create fat JARs for deployable modules:

```bash
./gradlew :server-v2:shadowJar
./gradlew :consumer-v3:shadowJar
./gradlew :projection-lambda:shadowJar
```

## Local Development

Start the Assignment 1 server:

```bash
./gradlew :server:run --args="8080"
```

Start `server-v2` against local/default RabbitMQ settings:

```bash
./gradlew :server-v2:run --args="8080 0 server-v2-local dev-token 9090"
```

Run the config-driven client:

```bash
./gradlew :client-part2:run --args="--config=config/client-part2-smoke.yml"
```

Run a larger Assignment 3 load profile after infrastructure is ready:

```bash
./gradlew :client-part2:run --args="--config=config/assignment3-baseline.yml"
```

The client writes metrics to `results/metrics.csv`, `results/summary.csv`, and
`results/throughput_10s.csv`.

## Deployment

Deployment assets are grouped under `deployment/`:

- `deployment/docker/` builds multi-arch images for `server-v2`, `consumer`, and
  `consumer-v3`.
- `deployment/terraform/` provisions ALB, EC2 server nodes, RabbitMQ, consumers,
  DynamoDB/Redis/SQS resources, and Lambda wiring.
- `deployment/*-env-example.sh` documents environment variables for runtime
  services.

The Assignment 3 runbook has the most complete end-to-end deployment flow:

```text
docs/assignment3-runbook.md
```

## Load Testing And Monitoring

Recommended Assignment 3 sequence:

1. Baseline: `config/assignment3-baseline.yml`
2. Stress: `config/assignment3-stress.yml`
3. Endurance: `config/assignment3-endurance.yml`

Use these supporting docs:

- `load-tests/README.md` for the test tiers and pass criteria.
- `load-tests/test-matrix.md` for batch/flush tuning.
- `monitoring/README.md` for RabbitMQ, CloudWatch, Redis, and system metrics.
- `docs/report/assignment2-report.md` and `docs/requirement/assignment3-report.md`
  for measured results and analysis.

## Documentation Index

| Document | Description |
| --- | --- |
| `docs/assignment3-runbook.md` | Step-by-step Assignment 3 deployment and verification notes. |
| `database/schema.md` | DynamoDB table and key design. |
| `database/design-document.md` | Persistence and query design rationale. |
| `deployment/README.md` | Deployment artifact overview. |
| `deployment/docker/README.md` | Container build and publish commands. |
| `load-tests/README.md` | Load-test tiers and metrics checklist. |
| `monitoring/README.md` | Metrics collection commands. |
| `docs/report/assignment2-report.md` | Assignment 2 architecture and performance report. |
| `docs/requirement/assignment3-report.md` | Assignment 3 persistence and performance report. |
| `docs/requirement/assignment*.md` | Original assignment requirements. |

## Notes

- The old single-core performance summary was specific to an earlier Assignment 1
  run. Current performance evidence belongs in the assignment reports and
  `results/`, not as the project overview.
- Several modules require external services before they are useful locally:
  RabbitMQ for `server-v2`, DynamoDB/SQS for `consumer-v3`, and DynamoDB
  Streams/Redis for `projection-lambda`.
