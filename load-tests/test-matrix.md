# Batch / Flush Parameter Matrix — Assignment 3

## Purpose

Part 2 of Assignment 3 requires testing 5 combinations of
`CHATFLOW_V3_BATCH_SIZE` and `CHATFLOW_V3_FLUSH_INTERVAL_MS` to find the
optimal balance between throughput, latency, and DynamoDB pressure.

Use a **reduced load (200 k messages)** for all 5 runs so the sweep finishes
quickly and results are comparable.  Once you pick the winner, run it with the
full baseline (500 k) and stress (1 M) configs.

---

## 5 Configurations

| # | BATCH_SIZE | FLUSH_INTERVAL_MS | Expected behaviour |
|---|-----------|-------------------|-------------------|
| 1 | 100       | 100 ms            | Low latency, many small batches, higher WCU overhead |
| 2 | 500       | 100 ms            | Medium batch, frequent flush — good baseline |
| 3 | 500       | 500 ms            | Medium batch, less frequent flush — reduces DynamoDB calls |
| 4 | 1 000     | 500 ms            | Large batch — fewer DynamoDB calls, higher per-message latency |
| 5 | 5 000     | 1 000 ms          | Maximum batch — best throughput, worst tail latency |

To run configuration N, export the env vars before starting consumer-v3:
```bash
export CHATFLOW_V3_BATCH_SIZE=500
export CHATFLOW_V3_FLUSH_INTERVAL_MS=500
# then start consumer-v3
```

---

## Metrics to Record per Configuration

For each of the 5 configurations, fill in the table below after the run:

| Metric                        | Config 1 | Config 2 | Config 3 | Config 4 | Config 5 |
|-------------------------------|----------|----------|----------|----------|----------|
| **Total duration (s)**        |          |          |          |          |          |
| **Mean throughput (msg/s)**   |          |          |          |          |          |
| **Peak throughput (msg/s)**   |          |          |          |          |          |
| **p50 write latency (ms)**    |          |          |          |          |          |
| **p95 write latency (ms)**    |          |          |          |          |          |
| **p99 write latency (ms)**    |          |          |          |          |          |
| **RabbitMQ peak queue depth** |          |          |          |          |          |
| **Projection lag at end (ms)**|          |          |          |          |          |
| **DLQ message count**         |          |          |          |          |          |
| **DynamoDB throttles**        |          |          |          |          |          |
| **DynamoDB consumed WCU**     |          |          |          |          |          |
| **Lambda iterator age (ms)**  |          |          |          |          |          |

---

## Analysis Checklist

After filling the table:

- [ ] Which configuration gives the highest mean throughput?
- [ ] Which gives the lowest p99 write latency?
- [ ] Is there a throughput cliff at any batch size?
- [ ] Does a larger batch size meaningfully reduce DynamoDB WCU consumption?
- [ ] Which configuration would you choose for production, and why?

Record your conclusions in `results/` or the performance report.

---

## Environment Variables Summary

```bash
# consumer-v3 relevant vars (from config/consumer-v3-local.env.example)
CHATFLOW_V3_BATCH_SIZE=<value>
CHATFLOW_V3_FLUSH_INTERVAL_MS=<value>
CHATFLOW_V3_SEMAPHORE_PERMITS=8     # max concurrent DynamoDB requests

# Keep these fixed across all 5 runs for fair comparison
CHATFLOW_V3_RETRY_BASE_MS=100
CHATFLOW_V3_RETRY_MAX_MS=3000
CHATFLOW_V3_MAX_RETRIES=3
```
