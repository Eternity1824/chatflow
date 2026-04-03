# Performance Report — Assignment 3 (ChatFlow Persistence)

> Fill this template after all load tests are complete.
> Reference screenshots and CSV files from `results/`.

---

## 1. Executive Summary

| Item                    | Value |
|-------------------------|-------|
| Test date range         |       |
| Environment             |       |
| Total messages tested   |       |
| Peak throughput achieved|       |
| Selected optimal config |       |

---

## 2. Write Performance

### 2.1 Baseline (500 k)

| Metric          | Value |
|-----------------|-------|
| Duration (s)    |       |
| Mean tput (msg/s)|      |
| p50 (ms)        |       |
| p95 (ms)        |       |
| p99 (ms)        |       |
| DLQ messages    |       |

### 2.2 Stress (1 M)

| Metric          | Value |
|-----------------|-------|
| Duration (s)    |       |
| Mean tput (msg/s)|      |
| p50 (ms)        |       |
| p95 (ms)        |       |
| p99 (ms)        |       |
| DLQ messages    |       |

### 2.3 Batch/Flush Sweep

*(Summarise test-matrix.md findings here.)*

| Config (batch/flush) | Mean tput | p99 | DynamoDB throttles | Chosen? |
|----------------------|-----------|-----|--------------------|---------|
| 100 / 100 ms         |           |     |                    |         |
| 500 / 100 ms         |           |     |                    |         |
| 500 / 500 ms         |           |     |                    | ✓ / ✗  |
| 1000 / 500 ms        |           |     |                    |         |
| 5000 / 1000 ms       |           |     |                    |         |

**Optimal configuration chosen:** batch=`___`, flush=`___` ms

**Rationale:**

---

## 3. System Stability (Endurance)

| Metric                          | Start | Mid (15 min) | End (30 min) |
|---------------------------------|-------|--------------|--------------|
| Throughput (msg/s)              |       |              |              |
| p99 latency (ms)                |       |              |              |
| consumer-v3 heap (MB)           |       |              |              |
| Lambda iterator age (ms)        |       |              |              |
| DLQ depth                       |       |              |              |

**Stability verdict:**
- Heap growth: stable / growing / ⚠️ leak detected
- Lambda lag: recovered / never caught up
- p99 degradation: ___× baseline

---

## 4. Bottleneck Analysis

Primary bottleneck identified:

> _Describe: e.g., "DynamoDB PAY_PER_REQUEST throughput capped at ~3 000 WCU/s,
> causing consumer-v3 to back off. RabbitMQ queue depth peaked at ~50 k.
> Switching to provisioned throughput or batching at 1 000 items relieved pressure."_

Secondary observations:
-
-

---

## 5. Trade-offs Made

| Decision                          | Trade-off |
|-----------------------------------|-----------|
| DynamoDB PAY_PER_REQUEST vs PROVISIONED | No capacity planning needed, but no reserved throughput guarantee |
| Batch size 500 / 500 ms           | Lower per-message latency vs. fewer DynamoDB requests |
| CDC via Streams + Lambda          | Eventual consistency (projection lag) vs. synchronous fanout in consumer |
| Redis EVAL Lua for analytics      | Atomic dedupe + analytics vs. slightly higher Lambda duration |
| SQS DLQ for dead letters          | Permanent failure isolation vs. manual DLQ replay required |

---

## 6. Projection Health

| Metric                   | Value at test end |
|--------------------------|-------------------|
| projectionLagMs          |                   |
| isConsistent             |                   |
| lastProjectedMessageId   |                   |
| lastProjectedIngestedAt  |                   |
| thresholdMs              |                   |

---

## 7. Screenshots

List of screenshots attached in the report:

1. `baseline-throughput.png` — client-part2 throughput over time
2. `stress-throughput.png`
3. `cloudwatch-dynamodb-wcu.png`
4. `cloudwatch-lambda-iterator-age.png`
5. `cloudwatch-lambda-errors.png`
6. `rabbitmq-queue-depth.png`
7. `client-log-metrics-report.png` — JSON report output
8. `sqs-dlq-depth.png`
