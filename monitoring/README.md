# Monitoring — ChatFlow Assignment 3

This directory contains scripts and notes for collecting metrics during
and after load tests.  No Prometheus or Grafana is required; all metrics
are collected via AWS CLI, CloudWatch, curl, or redis-cli and written as
plain text / CSV.

---

## 1. RabbitMQ — Queue Depth & Rates

**Script:** `collect-rabbitmq-metrics.sh`

Queries the RabbitMQ HTTP management API for:
- `messages` — current queue depth
- `message_stats.publish_details.rate` — publish rate (msg/s)
- `message_stats.deliver_get_details.rate` — consume rate (msg/s)

**Prerequisites:** `curl`, `jq`, RabbitMQ management plugin enabled.

```bash
RABBIT_HOST=<ec2-ip> RABBIT_PORT=15672 \
  ./monitoring/collect-rabbitmq-metrics.sh
```

---

## 2. AWS Persistence Metrics (DynamoDB, Lambda, SQS)

**Script:** `collect-aws-persistence-metrics.sh`

Pulls via AWS CLI (`aws cloudwatch get-metric-statistics`):
- DynamoDB `ConsumedWriteCapacityUnits` and `SystemErrors`
- Lambda `IteratorAge`, `Errors`, `Duration`
- SQS `ApproximateNumberOfMessages` on the DLQ

**Prerequisites:** AWS CLI configured (`aws configure`), correct IAM permissions.

```bash
NAME_PREFIX=chatflow ./monitoring/collect-aws-persistence-metrics.sh
```

Output: CSV lines to stdout, can be redirected to a file for the report.

---

## 3. Redis — Memory & Ops

**Script:** `collect-redis-metrics.sh`

Connects to ElastiCache Redis and collects:
- `used_memory_human`
- `instantaneous_ops_per_sec`
- Analytics key spot-reads (`active_users:minute:*`, `messages:minute:*`)

**Prerequisites:** `redis-cli` on the collection host (must be in the same VPC
or use an SSH tunnel), `REDIS_HOST` and `REDIS_PORT` env vars.

```bash
REDIS_HOST=<elasticache-endpoint> REDIS_PORT=6379 \
  ./monitoring/collect-redis-metrics.sh
```

---

## 4. System CPU / Memory

**Script:** `collect-system-metrics.sh` (existing)

Snapshots CPU and memory on the machine where the script runs.
Run on each EC2 instance (server-v2, consumer-v3) during the test.

```bash
./monitoring/collect-system-metrics.sh monitoring/output
```

---

## 5. CloudWatch — Manual / Console Checks

The following are best viewed in the AWS Console during or after the test.
No script required, but screenshot these for the report.

| Metric                              | Service       | Namespace / Metric Name                              |
|-------------------------------------|---------------|------------------------------------------------------|
| DynamoDB consumed WCU               | DynamoDB      | `AWS/DynamoDB` → `ConsumedWriteCapacityUnits`        |
| DynamoDB throttled requests         | DynamoDB      | `AWS/DynamoDB` → `SystemErrors` / `ThrottledRequests`|
| Lambda iterator age                 | Lambda        | `AWS/Lambda` → `IteratorAge`                         |
| Lambda error count                  | Lambda        | `AWS/Lambda` → `Errors`                              |
| Lambda avg duration                 | Lambda        | `AWS/Lambda` → `Duration`                            |
| SQS DLQ depth                       | SQS           | `AWS/SQS` → `ApproximateNumberOfMessages`            |
| EC2 CPU (consumer-v3 / server-v2)   | EC2           | `AWS/EC2` → `CPUUtilization`                         |

**CloudWatch Logs Insights** — useful query for Lambda errors:
```
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
| limit 50
```

---

## 6. Projection Lag Check

After any test, hit the metrics API:
```bash
curl "http://<server-v2-ip>:8080/api/metrics/report?start=<startMs>&end=<endMs>" | jq .health
```

`projectionLagMs` < 5000 and `isConsistent = true` means the Lambda has
fully caught up.  A persistent lag > 30 000 ms indicates Lambda iterator age
is growing (Lambda concurrency or DynamoDB write throttling).

---

## 7. Collect Everything at Once

Run all scripts in sequence before stopping the test infrastructure:

```bash
# On the monitoring / bastion host
RABBIT_HOST=<ip>      ./monitoring/collect-rabbitmq-metrics.sh       > monitoring/output/rabbitmq.csv
NAME_PREFIX=chatflow  ./monitoring/collect-aws-persistence-metrics.sh > monitoring/output/aws.csv
REDIS_HOST=<endpoint> ./monitoring/collect-redis-metrics.sh           > monitoring/output/redis.csv
./monitoring/collect-system-metrics.sh monitoring/output
```
