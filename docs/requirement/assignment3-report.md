# Assignment 3 Report

## Basic Info
- Course: CS6650
- Assignment: Assignment 3 - Persistence and Data Management
- Name: `Lihan Zhou`
- Date: `2026-03-25`
- Git Repository URL: `https://github.com/Eternity1824/chatflow.git`

## 1. Current Project State

### 1.1 Implementation summary

- `consumer-v3` implements canonical persistence to DynamoDB with batching, retry, circuit breaker, and SQS DLQ support.
- `projection-lambda` supports the split analytics pipeline:
  `DynamoDB Streams -> Lambda A (projection writer) -> SQS analytics queue -> Lambda B (Redis analytics)`.
- `server-v2` exposes the required query and metrics endpoints, including `GET /api/metrics/report`.
- `client-part2` can fetch and log the metrics report automatically after a load test.
- The evaluation used three workload tiers: baseline, stress, and endurance.
- `./gradlew test` passed on 2026-03-29.

### 1.2 Important architecture note

The final implementation direction is slightly different from some earlier design notes.
This report uses the newer pipeline below as the source of truth:

```text
Client -> ALB -> server-v2 -> RabbitMQ -> consumer-v3
                                     -> DynamoDB messages_by_id
                                     -> DynamoDB Streams
                                     -> Lambda A (CDC projector)
                                        -> room_messages
                                        -> user_messages
                                        -> user_rooms
                                        -> SQS analytics queue
                                     -> Lambda B (Redis analytics)
                                        -> Redis / ElastiCache
```

This report and the supporting Assignment 3 documentation now use the split
`Lambda A -> SQS -> Lambda B -> Redis` pipeline consistently, matching the
current Terraform and Lambda code.

### 1.3 Preliminary evidence already available

Several AWS console screenshots were captured during the infrastructure tuning
phase. These are not the final baseline/stress/endurance result figures, but
they are useful as early evidence that the persistence pipeline components were
created, wired, and observed in AWS.

Available evidence includes:

- a DynamoDB console view confirming that the canonical table and projection tables were created and active
- a CloudWatch view for Lambda A (`chatflow-a2-cdc-projector`)
- a CloudWatch view for Lambda B (`chatflow-a2-redis-analytics`)
- an SQS monitoring view for the analytics queue
- an ElastiCache monitoring view for the Redis analytics node

These screenshots are used as infrastructure tuning / pipeline validation
evidence, not as substitutes for the final load-test throughput and latency
charts.

One practical limitation affected the final evidence collection: the AWS lab
account was forcibly deactivated shortly after the endurance run, which blocked
further CloudWatch / SQS console access. For that reason, the report relies on
the screenshots that were already captured during tuning and on the client-side
metrics logs collected before the account lockout.

---

## 2. System Design Overview

### 2.1 Persistence write path

`consumer-v3` consumes protobuf messages from RabbitMQ, groups them in `BatchAccumulator`, and writes each record to the canonical DynamoDB table `messages_by_id` using an idempotent conditional write:

```text
ConditionExpression: attribute_not_exists(messageId)
```

RabbitMQ acknowledgements are sent only after the canonical write succeeds or is confirmed as a duplicate.

### 2.2 Projection and analytics path

The canonical table has DynamoDB Streams enabled with `NEW_IMAGE`.
Lambda A (`CdcProjectorHandler`) reads stream records, writes the DynamoDB projections, and publishes `AnalyticsEvent` messages to SQS.
Lambda B (`RedisAnalyticsHandler`) consumes those SQS messages and performs Redis analytics updates in batch.

This split design keeps the projection fan-out isolated from Redis latency and makes analytics failures retryable through SQS without blocking DynamoDB projection writes.

### 2.3 Query and metrics path

`server-v2` exposes:

- `GET /api/query/rooms/{roomId}/messages?start=&end=`
- `GET /api/query/users/{userId}/messages?start=&end=`
- `GET /api/query/users/{userId}/rooms`
- `GET /api/query/active-users?start=&end=`
- `GET /api/analytics/summary?start=&end=&topN=`
- `GET /api/metrics/report?start=&end=&topN=`

`client-part2` automatically calls `/api/metrics/report` after a test run unless `CHATFLOW_REPORT_ENABLED=false`.

---

## 3. Database Choice and Data Model

### 3.1 Database choice

The system uses DynamoDB for durable message persistence and Redis for analytics.

#### Why DynamoDB

- High write throughput without managing database servers
- Native conditional writes for idempotency
- Native DynamoDB Streams for CDC
- Good fit for pre-defined access patterns required by the assignment

#### Why Redis

- Very low latency for counters and leaderboards
- Efficient support for message-rate statistics and top-N queries
- Good fit for temporary analytics aggregation and projection health markers

### 3.2 Canonical table

**Table:** `messages_by_id`

| Attribute | Purpose |
|---|---|
| `messageId` | Partition key, globally unique |
| `roomId` | Room identifier |
| `userId` | Sender identifier |
| `username` | Display name |
| `message` | Message body |
| `eventTs` | Original event time in milliseconds |
| `messageType` | TEXT / JOIN / LEAVE |
| `roomSequence` | Per-room ordering field |
| `ingestedAt` | Canonical write completion time |
| `serverId` | Origin server |
| `dayBucket` | UTC day bucket derived from event time |
| `clientIp` | Sender client IP observed by the server |

This table is the single source of truth for persistence.

### 3.3 Projection tables

**Table:** `room_messages`

- Query target: messages for a room in order
- Key schema: `pk = roomId#yyyyMMdd`, `sk = eventTsMs#messageId`
- Rationale: daily bucketing avoids hot partitions while preserving ordered reads within a room window

**Table:** `user_messages`

- Query target: messages sent by one user
- Key schema: `pk = userId#yyyyMMdd`, `sk = eventTsMs#messageId`
- Rationale: one user-day partition supports efficient history scans over bounded time ranges

**Table:** `user_rooms`

- Query target: rooms a user has participated in
- Key schema: `pk = userId`, `sk = roomId`
- Additional attribute: `lastActivityTs`
- Rationale: one partition per user supports fast "rooms participated in" lookups and ordering by last activity in application logic

### 3.4 Redis analytics keys

The current API and Lambda code imply the following logical analytics families:

- `messages:second:*`
- `messages:minute:*`
- `active_users:minute:*`
- `top_users:minute:*`
- `top_rooms:minute:*`
- `projection:lastProjectedIngestedAt`
- `projection:lastProjectedMessageId`

These support:

- messages per second/minute
- active user counting
- top active users
- top active rooms
- projection health / lag reporting

### 3.5 Backup and recovery approach

The backup and recovery strategy is centered on DynamoDB as the canonical store.
For durable recovery of persisted chat data, the primary protection mechanism is
DynamoDB native backup capability, especially point-in-time recovery (PITR) and
on-demand backups for the canonical table `messages_by_id`. This is the most
important table to protect because it is the authoritative source of message
history.

The projection tables (`room_messages`, `user_messages`, `user_rooms`) and the
Redis analytics layer are treated as derived state. In a recovery scenario,
those structures can be rebuilt from the canonical DynamoDB records by replaying
the projection pipeline rather than treating each derived store as the primary
recovery target. This design keeps the recovery model simple:

- protect the canonical table with managed DynamoDB backup features
- treat projection tables as rebuildable indexes over canonical data
- treat Redis analytics as rebuildable cache / derived analytics state
- retain failed write events in the DLQ so exceptional failures can be replayed

This approach relies heavily on cloud-managed recovery features, but it is an
intentional design choice: it reduces operational complexity while preserving a
clear recovery path for both durable message history and downstream query /
analytics views.

---

## 4. Core Query Support

### 4.1 Get messages for a room in time range

**Status:** implemented through `server-v2` query API and DynamoDB projection reads.

**Endpoint:** `GET /api/query/rooms/{roomId}/messages?start=&end=`

**Expected evidence to add later:**

- latency screenshot or log sample
- example JSON response
- measured response time for a representative room window

### 4.2 Get user message history

**Status:** implemented.

**Endpoint:** `GET /api/query/users/{userId}/messages?start=&end=`

**Evidence to add later:**

- query timing
- sample output

### 4.3 Count active users in time window

**Status:** implemented via Redis analytics.

**Endpoint:** `GET /api/query/active-users?start=&end=`

### 4.4 Get rooms a user participated in

**Status:** implemented.

**Endpoint:** `GET /api/query/users/{userId}/rooms`

---

## 5. Consumer Modifications

### 5.1 Write-behind design

The Assignment 3 consumer is implemented as `consumer-v3` and separates message intake from persistence:

- RabbitMQ delivery receives messages continuously
- `BatchAccumulator` buffers messages until `batchSize` or `flushIntervalMs`
- a virtual-thread executor performs persistence work
- DynamoDB concurrency is capped with `CHATFLOW_V3_SEMAPHORE_PERMITS`

This satisfies the assignment requirement to decouple queue consumption from database writes.

### 5.2 Batch and flush controls

The consumer already supports:

- `CHATFLOW_V3_BATCH_SIZE`
- `CHATFLOW_V3_FLUSH_INTERVAL_MS`
- `CHATFLOW_V3_SEMAPHORE_PERMITS`

The required 5-run sweep was completed using those exact environment variables.

### 5.3 Idempotent writes

The canonical table uses `messageId` as the unique identifier with conditional `PutItem`, so duplicate deliveries are safely ignored.

### 5.4 Error recovery

The current implementation already includes:

- retry with exponential backoff
- failure classification
- circuit breaker
- SQS DLQ for unrecoverable failures

#### Consumer configuration placeholders

| Parameter | Current / chosen value |
|---|---|
| `CHATFLOW_V3_BATCH_SIZE` | `1000` selected from the sweep |
| `CHATFLOW_V3_FLUSH_INTERVAL_MS` | `500` selected from the sweep |
| `CHATFLOW_V3_SEMAPHORE_PERMITS` | `64` |
| `CHATFLOW_V3_RETRY_BASE_MS` | `50` |
| `CHATFLOW_V3_RETRY_MAX_MS` | `5000` |
| `CHATFLOW_V3_MAX_RETRIES` | `5` |
| Circuit breaker enabled | `true` |
| Circuit breaker threshold | `5` consecutive failed batches |
| Circuit breaker open duration | `30000 ms` |

---

## 6. Indexing and Performance Strategy

### 6.1 Indexing / key strategy

The persistence design avoids relational secondary-index-heavy writes and instead uses purpose-built tables:

- canonical writes go only to `messages_by_id`
- room history reads go to `room_messages`
- user history reads go to `user_messages`
- user-to-room lookups go to `user_rooms`

This keeps write amplification predictable and aligns storage layout with the exact query patterns in the assignment.

### 6.2 Connection and client strategy

- DynamoDB uses AWS SDK clients from Java services and Lambda handlers
- Redis is accessed through Lettuce
- the API layer runs blocking query work on a dedicated executor so Netty I/O threads are not blocked

### 6.3 Resilience strategy

The current design uses circuit breaker and DLQ handling rather than synchronous fail-fast writes in the hot path.

Trade-off:

- better resilience and isolation under database or Redis failure
- slightly more complexity and eventual consistency in the projection/analytics path

---

## 7. Metrics API

### 7.1 API purpose

The assignment requires a JSON API that returns core query and analytics results after the test.
This is already implemented through `GET /api/metrics/report`.

### 7.2 Report contents

The response currently includes:

- report generation timestamp
- requested time window
- active user count
- messages per minute
- messages per second
- top users
- top rooms
- projection health:
  - `projectionLagMs`
  - `isConsistent`
  - `lastProjectedIngestedAt`
  - `lastProjectedMessageId`
  - `thresholdMs`

### 7.3 Consistency model for report interpretation

The metrics report is intentionally eventually consistent.
The hot message path ends at canonical DynamoDB persistence, while projections
and Redis analytics are updated asynchronously through the CDC pipeline:

```text
messages_by_id -> DynamoDB Stream -> Lambda A -> SQS -> Lambda B -> Redis
```

As a result, the first report fetched immediately after a load test may show a
partial view of the final analytics window. For final evaluation, the report
should be captured twice:

- immediate report at test completion
- catch-up report 15 to 30 seconds later for the same time window

This is expected behaviour, not a correctness failure, as long as the delayed
report converges toward the acknowledged message count and the queues drain.

### 7.4 Client integration

`client-part2` automatically fetches the report at the end of the run and logs it.

#### Representative example JSON

```json
{
  "generatedAt": "2026-04-03T08:12:31.562100790Z",
  "windowStartMs": 1775203750742,
  "windowEndMs": 1775203951098,
  "coreQueries": {
    "activeUsers": {
      "start": 1775203750742,
      "end": 1775203951098,
      "activeUserCount": 99890
    }
  },
  "analytics": {
    "messagesPerMinute": [
      { "count": 180057, "bucket": 29586729 },
      { "count": 197792, "bucket": 29586730 },
      { "count": 198504, "bucket": 29586731 },
      { "count": 90747, "bucket": 29586732 }
    ],
    "messagesPerSecond": [
      { "count": 3709, "bucket": 1775203751 },
      { "count": 4245, "bucket": 1775203752 }
    ],
    "topUsers": [
      { "userId": "86245", "messageCount": 21 }
    ],
    "topRooms": [
      { "roomId": "15", "messageCount": 33810 }
    ]
  },
  "health": {
    "projectionLagMs": 667,
    "isConsistent": true,
    "lastProjectedIngestedAt": "2026-04-03T08:12:31.555Z",
    "lastProjectedMessageId": "63de0f40-09cc-4867-b9cc-b4e337c20e60",
    "thresholdMs": 5000
  }
}
```

---

## 8. Load-Test Methodology

### 8.0 Pre-load infrastructure validation

Before presenting final load-test numbers, the system already has a first round
of infrastructure-level evidence:

| Component | Evidence | Current interpretation |
|---|---|---|
| DynamoDB | AWS console table view | canonical and projection tables exist and are active |
| Lambda A | CloudWatch tuning screenshot | CDC projector is invoked; concurrency remains bounded; no throttle spike is visible in the screenshot |
| Lambda B | CloudWatch tuning screenshot | Redis analytics consumer is invoked; duration is much lower than Lambda A; no visible throttle signal |
| SQS analytics queue | SQS monitoring screenshot | queue is actively drained; oldest-message age remains low in the screenshot |
| ElastiCache Redis | ElastiCache monitoring screenshot | CPU remains low, while memory / capacity usage rises during analytics activity |

These observations are useful for the write-up because they show the split
pipeline is alive end-to-end:

```text
Lambda A -> SQS analytics queue -> Lambda B -> Redis
```

However, they still need to be paired with formal client-side throughput and
latency results before they can support the final performance claims.

### 8.0.1 Screenshot evidence

**DynamoDB tables**

Shows the canonical table and the three projection tables are created and
active in AWS.

![DynamoDB tables](../../results/pics-a3/dynamodb-table.png)

**Lambda A (`chatflow-a2-cdc-projector`)**

Shows Lambda A invocations, duration, error count, and concurrency during the
tuning run. The screenshot supports the claim that the DynamoDB Stream consumer
was active and handling projection work.

![Lambda A tuning](../../results/pics-a3/LambdaA-tuning.png)

**Lambda B (`chatflow-a2-redis-analytics`)**

Shows Lambda B invocations, duration, and concurrency. Compared with Lambda A,
the observed runtime is lower, which is consistent with the split design where
Redis analytics is isolated into its own stage.

![Lambda B tuning](../../results/pics-a3/LambdaB-tuning.png)

**SQS analytics queue**

Shows the intermediate queue between Lambda A and Lambda B. This is direct
evidence that the implemented architecture uses queue decoupling rather than a
single Lambda writing both projections and Redis in one step.

![SQS tuning](../../results/pics-a3/sqs-tuning.png)

**ElastiCache Redis**

Shows Redis-side utilization during the tuning run. CPU remains low while
memory and capacity usage rise, which is consistent with analytics data being
accumulated asynchronously.

![ElastiCache tuning](../../results/pics-a3/Elasticcache-tuning.png)

### 8.1 Planned test sequence

The evaluation used the three required Assignment 3 runs:

| Test | Purpose |
|---|---|
| Baseline | Validate end-to-end persistence at 500k messages |
| Stress | Find throughput ceiling at 1M messages |
| Endurance | Validate sustained stability around 80% of peak |

### 8.2 Batch/flush sweep

The 5-configuration sweep was completed with a reduced 200k-message workload
before the main tests.

| Config | Batch size | Flush interval | Mean throughput | p50 | p95 | p99 | DynamoDB throttles | Chosen |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 100 | 100 ms | `2901.47` | `29` | `35` | `43` | `3337` | No |
| 2 | 500 | 100 ms | `2909.12` | `28` | `35` | `48` | `0` | No |
| 3 | 500 | 500 ms | `2909.38` | `28` | `35` | `40` | `0` | No |
| 4 | 1000 | 500 ms | `2913.31` | `28` | `35` | `40` | `0` | Yes |
| 5 | 5000 | 1000 ms | `2918.18` | `29` | `35` | `42` | `0` | No |

**Selected production-like setting:** batch=`1000`, flush=`500 ms`

**Selection rationale:** `1000/500` gave the best overall balance across
throughput, tail latency, and CDC catch-up. It matched the best `p99` seen in
the sweep, improved mean throughput over the smaller-batch settings, and
reduced Lambda A duration and iterator age compared with the `500/*`
configurations. The `5000/1000` run achieved slightly higher raw throughput,
but its delayed catch-up report was incomplete, so it was rejected as the main
test configuration.

**Measurement note:** for each run, record both the immediate metrics report and
one delayed catch-up report for the same window so eventual consistency is
measured explicitly rather than treated as an error.

### 8.3 How application-level errors are interpreted

The load generator intentionally mixes multiple message types and does not treat
every rejected message as an infrastructure failure. In the current server
implementation, a representative source of `error responses` is protocol order
violation, especially sending `TEXT` before a successful `JOIN`. These requests
are intentionally rejected at the application layer and returned as error
responses rather than transport failures.

For this reason, the report distinguishes:

- `Error responses`: application-level protocol rejections produced by the synthetic workload
- `Connection failures` and `Failed messages`: transport or delivery failures

In the tuning runs, `Connection failures` remained zero while a small fraction
of requests were classified as `UNKNOWN`/error responses. These should be
interpreted as workload artefacts, not evidence that the persistence pipeline
was unavailable.

---

## 9. Performance Results

### 9.1 Baseline test

**Target workload:** 500,000 messages

| Metric | Value |
|---|---|
| Test date/time | `2026-04-03 00:56-00:59 PDT` |
| Total messages sent | `500,000` |
| Total messages acknowledged | `487,987` |
| Duration (s) | `167.084` |
| Mean throughput (msg/s) | `2,920.61` |
| Peak throughput (msg/s) | `3,719` |
| p50 latency (ms) | `29` |
| p95 latency (ms) | `35` |
| p99 latency (ms) | `38` |
| DLQ message count | `0` |
| DynamoDB throttles | `room_messages WriteThrottleEvents peak = 45,879` |
| Lambda A errors | `0` |
| Lambda B errors | `0` |
| Projection lag at end (ms) | `21,617` |
| `isConsistent` | `false` at test end; eventual catch-up confirmed later |
| Active user count | `98,178` immediately after test; `99,266` after catch-up |
| Top room | `roomId=10, messageCount=24,611` |
| Top user | `userId=68389, messageCount=16` |

**Baseline notes:** The first 500k baseline on the new AWS account showed high CDC lag, so the final baseline was rerun after increasing `lambda_memory_mb` to `1024` and `lambda_cdc_parallelization_factor` to `4`. With that tuning, the chat ingress path remained stable (`0` connection failures, `0` failed messages), but the projector was still catching up at the exact end of the run. The delayed report later summed to the full `487,987` acknowledged messages, which is consistent with the system's eventual-consistency model for analytics.

### 9.2 Stress test

**Target workload:** 1,000,000 messages

| Metric | Value |
|---|---|
| Test date/time | `2026-04-03 01:09-01:12 PDT` |
| Total messages sent | `1,000,000` |
| Total messages acknowledged | `957,088` |
| Duration (s) | `200.356` |
| Mean throughput (msg/s) | `4,776.94` |
| Peak throughput (msg/s) | `7,963` |
| Sustained throughput (msg/s) | `4,776.94` |
| p50 latency (ms) | `29` |
| p95 latency (ms) | `35` |
| p99 latency (ms) | `38` |
| DLQ message count | `0` |
| DynamoDB throttles | `0 observed in captured stress metrics` |
| Lambda A iterator age peak (ms) | `7,526` |
| Lambda A errors | `0` |
| Lambda B errors | `0` |
| Projection lag at end (ms) | `667` |
| `isConsistent` | `true` |

**Observed primary bottleneck:** After the Lambda tuning, no hard infrastructure bottleneck appeared in the 1M stress run. The most stressed component was still Lambda A plus DynamoDB projection writes, but iterator age stayed below `7.6s`, analytics remained current, and Redis-side processing stayed cheap (`~7-9 ms` average Lambda B duration).

**Observed degradation pattern:** No meaningful degradation was observed over the stress window. Latency percentiles stayed flat (`p95=35 ms`, `p99=38 ms`), connection failures remained `0`, DLQ stayed empty, and the metrics API was already consistent at the end of the test. The remaining client-side `error responses` were application-level invalid message sequences from the synthetic workload rather than transport or persistence failures.

### 9.3 Endurance test

**Target workload:** approximately 30 minutes at 80% of peak throughput

| Metric | t=0 | t=10 min | t=20 min | t=30 min |
|---|---|---|---|---|
| Throughput (msg/s) | `~3.6k-4.1k observed` | `~3.6k sustained` | `~3.6k sustained` | `3,665.61 overall mean` |
| p99 latency (ms) | `42` | `42` | `42` | `42` |
| RabbitMQ queue depth | `low` | `growing backlog` | `~146k total queued observed` | `cleared after test` |
| consumer-v3 heap used (MB) | `not retained` | `not retained` | `not retained` | `not retained` |
| Lambda A iterator age (ms) | `not retained` | `not retained` | `not retained` | `not retained` |
| Lambda B backlog / queue depth | `0 visible / 573 notVisible` | `3043 visible / 230 notVisible` | `6981 visible / 391 notVisible` | `not queryable after lab policy change` |
| Projection lag (ms) | `510` | `983` | `1750` | `466,947` |
| DLQ message count | `0 observed` | `0 observed` | `0 observed` | `0 observed` |

**Endurance verdict:** The 30-minute endurance run showed that the system remained available on the hot path, but sustained load eventually exposed the RabbitMQ broker as the dominant bottleneck. Up to `t20`, the downstream persistence and analytics pipeline stayed healthy (`isConsistent=true`, projection lag below `2s`). In the final 10 minutes, RabbitMQ backlog growth and burst-credit exhaustion on the `t`-class broker caused the CDC path to fall behind, and the final report for the full window remained incomplete (`4,881,051` projected vs. `6,607,638` acknowledged). This result is still useful: it demonstrates that the chosen architecture is stable for baseline and stress workloads, while sustained endurance at this rate is limited primarily by broker sizing rather than by canonical DynamoDB writes or the split Lambda analytics design.

---

## 10. Resource Utilization and Stability

### 10.1 Metrics to record

| Component | Metrics to capture | Source |
|---|---|---|
| RabbitMQ | queue depth, publish rate, consume rate | RabbitMQ management metrics |
| DynamoDB | consumed WCU, throttles, system errors | CloudWatch / exported AWS metrics |
| Lambda A | iterator age, duration, errors | CloudWatch |
| Lambda B | duration, errors, concurrency | CloudWatch |
| SQS | DLQ depth, analytics queue depth | CloudWatch / AWS CLI |
| Redis | ops/s, memory usage | Redis metrics |
| consumer-v3 / server-v2 | CPU and memory | EC2 / OS metrics |

### 10.2 Stability interpretation

The final measurements support the following stability interpretation:

- Queue depth remained bounded for baseline and stress, but not for the full 30-minute endurance run.
- Projection lag recovered acceptably in baseline and remained healthy in stress, but it did not recover for the late-window portion of endurance.
- Lambda B was not the dominant bottleneck; the pressure point shifted first to RabbitMQ and then to downstream projection freshness.
- Consumer heap was not retained in the final evidence set because the AWS lab account was deactivated before additional instance-level screenshots could be captured.
- DLQ depth remained effectively zero in all recorded runs.

### 10.3 Tuning evidence already collected

The report includes supporting AWS console screenshots for DynamoDB, Lambda A,
Lambda B, SQS, and ElastiCache from the pre-main-test tuning phase. These are
used as supporting evidence for infrastructure readiness and pipeline
validation.

---

## 11. Bottleneck Analysis

### 11.1 Expected bottleneck candidates

Before measurements, the main likely bottlenecks are:

- RabbitMQ queue buildup under burst load
- `consumer-v3` batch persistence throughput
- DynamoDB write throttling or partition pressure
- Lambda A stream backlog (`IteratorAge`)
- SQS-to-Lambda-B backlog for analytics
- Redis saturation during sustained analytics updates

### 11.2 Final bottleneck conclusion

**Primary bottleneck overall:** RabbitMQ broker sizing under sustained load,
with Lambda A stream catch-up as the secondary bottleneck before tuning.

**Evidence:**

- canonical DynamoDB writes stayed stable across the sweep and did not become
  the limiting stage in the final stress run
- before Lambda tuning, Lambda A iterator age and duration were the clearest
  cause of analytics lag; after increasing memory and parallelization, the 1M
  stress run finished with `projectionLagMs = 667` and `isConsistent = true`
- during endurance, RabbitMQ backlog grew substantially and the broker
  exhausted CPU credits, after which late-window projection freshness degraded
  even though the hot path continued to accept messages
- Lambda B duration remained low throughout the recorded tests, so Redis-side
  analytics processing was not the dominant limit

**Mitigation already adopted:**

- split the old monolithic CDC Lambda into `Lambda A -> SQS -> Lambda B`
- batch the DynamoDB projection writes and batch the Redis analytics updates
- select `CHATFLOW_V3_BATCH_SIZE=1000` and `CHATFLOW_V3_FLUSH_INTERVAL_MS=500`

**Trade-off accepted:**

- the system prioritizes stable write throughput and bounded catch-up time over
  strict real-time analytics consistency at test completion
- the final sustained-load limit in this environment was shaped more by lab
  instance sizing constraints than by the data model itself

---

## 12. Trade-offs Made

| Decision | Trade-off |
|---|---|
| DynamoDB as canonical store | simpler managed operations, but query patterns must be designed up front |
| Canonical table plus projections | faster reads, but additional CDC complexity |
| Split `Lambda A -> SQS -> Lambda B` analytics pipeline | isolates Redis failures, but adds eventual consistency and one extra queue hop |
| Redis analytics | fast counters and top-N queries, but analytics durability is weaker than canonical storage |
| Circuit breaker + DLQ | better resilience under failure, but failed records require replay workflow |

---

## 13. Final Conclusion

Assignment 3 extends ChatFlow with durable persistence, projection-based query support, and analytics reporting.
The implemented design uses DynamoDB as the canonical store, DynamoDB Streams for change capture, Lambda A for projection fan-out, SQS for analytics decoupling, Lambda B for Redis updates, and `server-v2` for the final query and metrics API.

Based on the completed measurements, the final conclusion should summarize:

- whether the system met throughput and latency goals
- which batch/flush configuration was selected
- whether the system remained stable during endurance testing
- what the primary bottleneck was
- what improvements would be made next

**Final conclusion:** Assignment 3 successfully extended ChatFlow with durable canonical storage in DynamoDB, projection tables for query access patterns, and decoupled analytics using `Lambda A -> SQS -> Lambda B -> Redis`. The selected `consumer-v3` settings were `CHATFLOW_V3_BATCH_SIZE=1000` and `CHATFLOW_V3_FLUSH_INTERVAL_MS=500`, and the final Lambda tuning used `lambda_memory_mb=1024` with `lambda_cdc_parallelization_factor=4`. Under the tuned baseline workload, the system sustained `2,920.61 msg/s` with `p99=38 ms`, and under the tuned 1M stress workload it sustained `4,776.94 msg/s` with `p99=38 ms` while remaining consistent at test end. The endurance run confirmed that the architecture stayed available for 30 minutes, but also revealed the primary sustained bottleneck: the single `t`-class RabbitMQ broker exhausted CPU credits and became the limiting stage, causing late-window backlog growth and projection catch-up lag. The next improvement would be to replace or resize the broker layer before further increasing sustained target QPS.

---

## 14. Lessons Learned

### 14.1 System sizing lesson

The clearest engineering lesson from Assignment 3 is that the dominant sustained-load bottleneck was not the ChatFlow server tier or the `consumer-v3` persistence tier, but the RabbitMQ broker tier. Across the baseline and stress runs, the server and consumer components remained stable, while the endurance run showed backlog growth first at the broker layer and then in downstream projection freshness. The observed degradation pattern was consistent with CPU credit exhaustion on the burstable EC2 instance hosting RabbitMQ. In the next iteration, I would rebalance the deployment by reducing the number of server and consumer instances and reallocating those resources to a better-sized broker instance, preferably a non-burstable EC2 type, or to a managed messaging service. That change would likely make throughput more stable, reduce backlog growth during sustained runs, and lower the operational overhead of over-provisioning non-bottleneck tiers.

### 14.2 Operational lesson from AWS account deactivation

A second lesson was operational rather than architectural. The AWS account used for the final Assignment 3 runs was deactivated at the end of the experiment window on April 3, 2026, and a newer AWS account was also later restricted while attempting to rebuild the environment. That prevented additional CloudWatch validation, follow-up screenshots, and reruns after the endurance test. As a result, some late-stage monitoring evidence is incomplete. In future experiments, I would capture all critical screenshots and exported metrics immediately after each run, keep the deployment smaller and more targeted, and prioritize a lower-risk resource footprint in the lab environment. Even with that limitation, the recorded evidence still supports the main conclusion that the dominant bottleneck in this assignment environment was the RabbitMQ broker tier rather than the application servers or the DynamoDB persistence design.
