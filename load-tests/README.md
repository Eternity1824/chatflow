# Load Tests — Assignment 3

## Test Suite Overview

Three test tiers validate ChatFlow persistence under increasing load.
Run them **in order**: baseline confirms correctness, stress finds the ceiling,
endurance validates stability.

---

## Test Tiers

### 1. Baseline — 500 k messages

Validates that persistence works end-to-end at moderate load.
Every message must reach `messages_by_id`, then fan out to the three projection
tables via the CDC Lambda.

| Parameter       | Value        |
|-----------------|--------------|
| Total messages  | 500,000      |
| Client config   | `config/assignment3-baseline.yml` |
| Expected duration | ~5–8 min   |

**Pass criteria:**
- 0 messages in SQS DLQ after test
- Projection lag < 5 s at test end
- `isConsistent = true` in `/api/metrics/report`

---

### 2. Stress — 1,000,000 messages

Pushes the system toward its throughput ceiling.
Identifies the first bottleneck (RabbitMQ, consumer-v3, DynamoDB, or Lambda).

| Parameter       | Value        |
|-----------------|--------------|
| Total messages  | 1,000,000    |
| Client config   | `config/assignment3-stress.yml` |
| Expected duration | ~10–20 min |

**Pass criteria:**
- Throughput does not collapse mid-test
- DLQ messages ≤ 0.1 % of total
- Projection eventually catches up (lag returns to < 5 s)

---

### 3. Endurance — sustained ~80 % max throughput for 30 min

Validates that there is no memory leak, connection leak, or GC degradation
over a sustained period.

`client-part2` does not have a built-in duration mode; instead:

1. Set `totalMessages` in `config/assignment3-endurance.yml` to a number that
   takes ~30 min at your observed ~80 % peak throughput.
   Example: if peak = 4 000 msg/s, then 80 % = 3 200 msg/s → 30 min = 5 760 000.
2. Alternatively run the stress config twice back-to-back.

**Pass criteria:**
- Consumer-v3 heap stays stable (no continuous growth in CloudWatch)
- p99 latency does not degrade by more than 2× vs baseline
- DLQ depth stays near 0

---

## Execution Order

```
# 1. Start infrastructure (see docs/assignment3-runbook.md)

# 2. Baseline
java -jar client-part2/build/libs/client-part2-all.jar \
     --config config/assignment3-baseline.yml

# 3. Check DLQ + projection lag before proceeding
#    (monitoring/collect-aws-persistence-metrics.sh)

# 4. Stress
java -jar client-part2/build/libs/client-part2-all.jar \
     --config config/assignment3-stress.yml

# 5. Endurance
java -jar client-part2/build/libs/client-part2-all.jar \
     --config config/assignment3-endurance.yml
```

---

## Metrics to Record per Test

For each run, capture the following (templates in `results/`):

| Category                 | Metric                                | Source                              |
|--------------------------|---------------------------------------|-------------------------------------|
| **Throughput**           | Total msg/s (mean, peak)              | client-part2 summary log            |
| **Latency**              | p50 / p95 / p99 (ms)                  | client-part2 `results/summary.csv`  |
| **Consumer-v3**          | Batch write rate, DLQ sends           | consumer-v3 log / CloudWatch        |
| **DynamoDB**             | Consumed WCU, throttled requests      | AWS Console / CloudWatch            |
| **Lambda**               | Iterator age, error count, duration   | CloudWatch Logs Insights            |
| **Projection lag**       | `projectionLagMs` at end              | `/api/metrics/report`               |
| **Redis**                | ops/s, memory used                    | `monitoring/collect-redis-metrics.sh` |
| **SQS DLQ**              | `ApproximateNumberOfMessages`         | `monitoring/collect-aws-persistence-metrics.sh` |
| **Screenshots**          | Console snapshots for report          | manual                              |

---

## Batch / Flush Experiments

See `test-matrix.md` for the 5-configuration parameter sweep.
The assignment says "run **up to 5 tests** and choose the optimal" — you do not
need to run the full 4 × 3 matrix.  Run the 5 configs in `test-matrix.md` with
a **reduced load (e.g. 200 k messages)** to find the winner quickly, then use
that config for the baseline and stress runs.
