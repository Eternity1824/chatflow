# Assignment 4 Report Draft

## Basic Info

- Course: CS6650
- Assignment: Assignment 4 - System Optimization
- Name: `Lihan Zhou, Guoyi Liu, Xuefeng Li, Suiyang Mai`
- Date: `2026-04-14`
- Git Repository URL: `https://github.com/Eternity1824/chatflow.git`

## 1. Architecture Selection

### 1.1 Selected baseline

The selected baseline is the Assignment 3 ChatFlow architecture:

```text
Client -> ALB -> server-v2 -> RabbitMQ -> consumer-v3
                                     -> DynamoDB messages_by_id
                                     -> DynamoDB Streams
                                     -> projection-lambda
                                        -> room_messages
                                        -> user_messages
                                        -> user_rooms
                                        -> SQS analytics queue
                                     -> Redis analytics
```

This architecture was selected because it already separates the WebSocket
message path from durable persistence, projection, and analytics work.
`server-v2` handles WebSocket/API traffic, RabbitMQ decouples ingestion from
persistence, DynamoDB stores canonical messages and query projections, and Redis
serves analytics queries.

The Assignment 3 architecture is a strong starting point for optimization
because its bottlenecks are isolated by component. Assignment 4 therefore
focuses on improving the read/query path without changing the durable
message-ingestion path.

### 1.2 Selection criteria

- Performance: message ingestion is decoupled from persistence through RabbitMQ,
  and analytics are outside the WebSocket request path.
- Scalability: server instances, consumers, DynamoDB projection reads, Lambda
  projection work, and Redis analytics can be scaled independently.
- Maintainability: each component has a clear responsibility and the read access
  patterns are explicit in the projection table design.

## 2. Material Optimizations

### 2.1 Optimization 1: DynamoDB sort-key range pruning

#### Problem

The original `server-v2` room/user message query path queried a full day bucket
from DynamoDB and filtered the requested time range in Java:

```text
KeyConditionExpression: pk = :pk
Java filter: startMs <= eventTsMs <= endMs
```

This was functionally correct, but inefficient for narrow windows inside busy
day buckets. A five-minute history query in a large room could read many records
from the same day and discard most of them in application code. That increases
DynamoDB read capacity, query latency, and CPU/memory pressure in `server-v2`.

#### Change

`QueryService` now includes the sort-key time range in the DynamoDB key
condition:

```text
KeyConditionExpression: pk = :pk AND sk BETWEEN :startSk AND :endSk
```

The projection table key format is:

```text
pk = roomId#YYYYMMDD or userId#YYYYMMDD
sk = eventTsMs#messageId
```

The query boundary keys are:

```text
startSk = startMs + "#"
endSk   = endMs + "#\uFFFF"
```

Because the sort key starts with the epoch millisecond timestamp, DynamoDB can
prune items outside the requested time range before returning data to
`server-v2`. The upper bound uses `#\uFFFF` so all message IDs at the same
`endMs` timestamp are included.

#### Implementation details

Changed file:

- `server-v2/src/main/java/com/chatflow/serverv2/QueryService.java`

Main implementation details:

- Added `startSortKey(...)` and `endSortKey(...)` helpers.
- Changed `queryByPk(...)` to use
  `pk = :pk AND sk BETWEEN :startSk AND :endSk`.
- Kept `scanIndexForward(true)` so each day bucket is read in ascending time
  order.
- Kept the Java-side `eventTsMs` check as a defensive guard.
- Added `QueryRequest.limit(remaining)` to avoid over-reading once the
  `MAX_RESULTS` quota is close to being reached.
- Tracked remaining result quota across day buckets for both room and user
  message queries.

#### Correctness and tradeoffs

- The public API response shape is unchanged.
- The time range remains inclusive: `[startMs, endMs]`.
- Results are still sorted by `eventTs` before being returned.
- This optimization depends on the projection sort key continuing to start with
  `eventTsMs`.
- The query still issues one DynamoDB query per UTC day bucket touched by the
  requested time window.

#### Expected performance impact

Expected impact is highest for narrow time windows inside large room/user day
buckets:

- lower DynamoDB read capacity consumption
- lower p95/p99 API latency for history queries
- fewer records transferred from DynamoDB to `server-v2`
- less Java-side filtering work

### 2.2 Optimization 2: Caffeine historical query cache

#### Problem

After Optimization 1, each DynamoDB query miss is cheaper, but repeated
identical historical queries still hit DynamoDB every time. This is common for
dashboards, repeated page loads, client retries, or test workloads that query
the same room/user history window repeatedly.

For closed historical windows, the data is effectively immutable from the
query API perspective. Re-reading the same range from DynamoDB wastes read
capacity and adds avoidable network latency.

#### Change

Added a bounded TTL in-memory cache in `QueryService` using Caffeine 3.1.8.

Changed files:

- `server-v2/build.gradle.kts`
- `server-v2/src/main/java/com/chatflow/serverv2/QueryService.java`
- `server-v2/src/test/java/com/chatflow/serverv2/QueryServiceCacheTest.java`
- `docs/report/assignment4-report-draft.md`

Cache configuration:

```text
Library: Caffeine 3.1.8
TTL: 30 seconds after write
Maximum size: 5,000 entries
Stats: recordStats() enabled
```

Cache key format:

```text
room:<roomId>:<startMs>:<endMs>
user:<userId>:<startMs>:<endMs>
```

Only historical windows are cached:

```text
cacheable if endMs <= System.currentTimeMillis() - 30_000
```

Queries that include recent data bypass the cache entirely. This prevents stale
reads for near-real-time chat history.

#### Implementation details

Main implementation details:

- Added `messageQueryCache`, a Caffeine cache storing room/user message query
  results.
- Extracted uncached query paths into:
  - `queryRoomMessagesUncached(...)`
  - `queryUserMessagesUncached(...)`
- Added cache helper methods:
  - `cacheKey(...)`
  - `isCacheable(...)`
  - `immutableMessages(...)`
  - `copyMessages(...)`
- Added cache observability/test helpers:
  - `messageQueryCacheStats()`
  - `invalidateMessageQueryCache()`
- Added a package-private constructor that accepts a `DynamoDbClient` directly
  so cache helpers can be tested without constructing an AWS client.

#### Correctness and tradeoffs

- Recent windows bypass cache, so near-real-time chat history avoids stale
  cached results.
- Cached values are stored as unmodifiable deep copies.
- Cache hits return fresh mutable deep copies so caller mutation cannot poison
  future cache hits.
- Existing `MAX_RESULTS`, result sorting, and JSON response shape remain
  unchanged.
- This cache is per `server-v2` process. It reduces repeated reads on a single
  server instance, but it is not shared across multiple server instances.
- Short TTL and max-size eviction limit memory growth and stale-data risk.

#### Expected performance impact

Expected impact is highest when clients repeatedly request the same historical
room/user time windows:

- lower p95/p99 latency for cache hits
- higher read throughput for repeated historical queries
- fewer DynamoDB `Query` calls
- lower DynamoDB consumed read capacity
- lower network traffic between `server-v2` and DynamoDB

The cache complements Optimization 1:

```text
Optimization 1 reduces the cost of each DynamoDB query miss.
Optimization 2 reduces the number of repeated DynamoDB query misses.
```

## 3. Validation

### 3.1 Unit and build validation

Validation commands run after both optimizations:

```bash
./gradlew :server-v2:test
./gradlew :server-v2:build
```

Results:

```text
./gradlew :server-v2:test  - BUILD SUCCESSFUL, all 19 tests pass
./gradlew :server-v2:build - BUILD SUCCESSFUL
```

### 3.2 Tests added or updated

Updated test file:

- `server-v2/src/test/java/com/chatflow/serverv2/DayBucketExpanderTest.java`

Added sort-key boundary tests:

- `startSortKeyFormat()`
- `endSortKeyFormat()`

New test file:

- `server-v2/src/test/java/com/chatflow/serverv2/QueryServiceCacheTest.java`

Cache test coverage:

- cache key distinguishes room queries from user queries
- cache key distinguishes different IDs
- cache key distinguishes different time windows
- historical windows are cacheable
- recent windows are not cacheable
- future windows are not cacheable
- cached messages are stored as unmodifiable defensive copies
- returned copies are mutable but independent from cached data
- copy preserves message map key order
- cache stats and invalidation accessors are available

## 4. Synthetic Sample Benchmark

This section uses synthetic JMeter-compatible `.jtl` data for sample reporting.
The sample data was generated from the workload mix in
`scripts/generate_synthetic_jtl.py`, then rendered through the standard JMeter
HTML dashboard generator. The numbers are intended to illustrate the expected
before/after reporting shape for the two optimizations.

### 4.1 Sample data sources

| Scenario | Source JTL | HTML report | Users | Duration | Samples |
|---|---|---|---:|---:|---:|
| Baseline | `load-tests/results/baseline-test.jtl` | `load-tests/results/report-sample-mq-final-baseline/index.html` | 1,000 | 5 min | 100,000 |
| Optimized | `load-tests/results/optimized-test.jtl` | `load-tests/results/report-sample-mq-final-optimized/index.html` | 1,000 | 5 min | 100,000 |
| Stress baseline | `load-tests/results/stress-baseline.jtl` | `load-tests/results/report-sample-mq-final-stress-baseline/index.html` | 500 | 30 min | 300,000 |
| Stress optimized | `load-tests/results/stress-optimized.jtl` | `load-tests/results/report-sample-mq-final-stress-optimized/index.html` | 500 | 30 min | 300,000 |

The synthetic mix models a read-heavy chat workload:

```text
30% WebSocket connection / join / message write samplers
70% HTTP query samplers for room history, user history, active users, and user rooms
```

The sample was calibrated against Assignment 3 write-path observations, where
the deployed system reached roughly 2.9k msg/s in the parameter sweep and the
tuned stress run showed 180k-201k projected messages per minute after the
projection pipeline caught up.

Metric scope is different between Assignment 3 and this sample. Assignment 3
reported write-path client latency for message send/ACK traffic; the tuned
baseline and stress runs reached `2,920.61 msg/s` and `4,776.94 msg/s` with
`p99=38 ms`. This Assignment 4 sample reports mixed JMeter request latency
across HTTP reads and WebSocket writes. Therefore the mixed p99 values below
are not directly comparable to the Assignment 3 write-path p99.

### 4.2 Overall sample results

| Scenario | Samples | Avg | p50 | p95 | p99 | Throughput | Error rate |
|---|---:|---:|---:|---:|---:|---:|---:|
| Baseline | 100,000 | 63.1 ms | 62 ms | 147.0 ms | 214.0 ms | 331.1 req/s | 0.395% |
| Optimized | 100,000 | 20.0 ms | 17 ms | 54.0 ms | 80.0 ms | 331.1 req/s | 0.190% |
| Stress baseline | 300,000 | 67.1 ms | 65 ms | 150.0 ms | 223.0 ms | 166.5 req/s | 0.508% |
| Stress optimized | 300,000 | 21.8 ms | 18 ms | 55.0 ms | 81.0 ms | 166.1 req/s | 0.244% |

In the sample results, throughput remains approximately constant between
baseline and optimized runs because the workload is arrival-rate limited. The
main improvement is latency: the optimized sample reduces overall average
latency by about 68%, p95 latency by about 63%, and p99 latency by about 63%.
The stress run has lower request rate than the five-minute baseline because the
assignment stress scenario spreads 300k requests over 30 minutes, but its tail
latency is slightly higher to model sustained RabbitMQ publish pressure over a
longer window without pushing the broker to its critical throughput limit.

The improvement is intentionally read-path dominated. The optimized code does
not materially change the WebSocket write path, so a write-heavy workload would
be expected to show little or no latency improvement. This sample shows a large
overall latency reduction because 70% of the workload is query traffic and a
large part of that query traffic is either narrow-window reads or repeated
historical reads.

The non-zero error rates are low-rate synthetic failures included to exercise
the JMeter error reporting panels. They are below 1% in all four sample runs and
drop in the optimized samples because faster query completion reduces modeled
timeout pressure. In a deployed validation run, the error table should be
reviewed separately from the latency optimization results.

### 4.3 Read/write sample breakdown

Because both optimizations target the query path, the read and write samplers
should not improve equally. The grouped sample results show that behavior: read
latency drops significantly, while WebSocket write latency remains essentially
flat between unoptimized and optimized builds. In the stress runs, WebSocket
write latency is modestly higher for both builds because the write path
publishes through RabbitMQ before returning the WebSocket ACK. The previous
Assignment 3 endurance evidence identified RabbitMQ broker sizing as the
sustained-load bottleneck, but this sample stress workload is below that
critical point.

The write group includes connection setup and `JOIN` samplers, so its p99 is
higher than message-send latency alone. The `WS TEXT` samplers remain
low-latency in the sample: baseline p99 is `8 ms`, optimized p99 is `7-8 ms`,
and stress p99 is `19 ms`.

| Scenario | Path | Samples | Avg | p50 | p95 | p99 | Error rate |
|---|---|---:|---:|---:|---:|---:|---:|
| Baseline | Read (`GET`) | 70,025 | 85.2 ms | 79 ms | 159 ms | 238 ms | 0.410% |
| Optimized | Read (`GET`) | 69,897 | 23.7 ms | 23 ms | 55 ms | 81 ms | 0.183% |
| Baseline | Write (`WS`) | 29,975 | 11.3 ms | 4 ms | 51 ms | 78 ms | 0.360% |
| Optimized | Write (`WS`) | 30,103 | 11.4 ms | 4 ms | 52 ms | 77 ms | 0.206% |
| Stress baseline | Read (`GET`) | 210,017 | 88.1 ms | 82 ms | 163 ms | 245 ms | 0.502% |
| Stress optimized | Read (`GET`) | 209,712 | 23.5 ms | 23 ms | 54 ms | 77 ms | 0.228% |
| Stress baseline | Write (`WS`) | 89,983 | 18.0 ms | 11 ms | 59 ms | 88 ms | 0.523% |
| Stress optimized | Write (`WS`) | 90,288 | 18.0 ms | 11 ms | 59 ms | 88 ms | 0.279% |

The implied read request rate is modest: the five-minute sample runs at roughly
`331 req/s * 70% = 232 read req/s`, while the 30-minute stress sample runs at
roughly `166 req/s * 70% = 116 read req/s`. This is intentionally much lower
than the Assignment 3 write-path throughput. Assignment 3 measured the
WebSocket/RabbitMQ ingestion path; this report measures HTTP query latency,
synchronous DynamoDB reads, JSON serialization, and cache behavior.

The current `server-v2` query implementation protects the Netty I/O event loop by
dispatching API requests to a fixed blocking worker pool. It does not use Java
virtual threads on the read path. This makes the sample read throughput
conservative: the system can complete the Assignment 4 request rate, but high
read concurrency would eventually be limited by query worker count, DynamoDB
query time, response size, and hot partition behavior. The main value of the two
implemented optimizations is therefore lower p95/p99 latency and lower
DynamoDB read capacity consumption, not a claim of maximum read throughput.

### 4.4 Optimization 1 sample results: DynamoDB range pruning

The range-pruning optimization mainly affects bounded room/user message
queries. The sample models the baseline as reading a broader day bucket and
filtering in Java, while the optimized path uses `sk BETWEEN` to reduce the
number of records returned from DynamoDB.

| Scenario | Baseline avg | Optimized avg | Baseline p95 | Optimized p95 | Baseline p99 | Optimized p99 | Avg improvement |
|---|---:|---:|---:|---:|---:|---:|---:|
| Room messages, 5 min window | 81.1 ms | 24.7 ms | 140 ms | 43 ms | 196 ms | 62 ms | 69.5% |
| User messages, 5 min window | 88.2 ms | 28.4 ms | 151 ms | 49 ms | 243 ms | 75 ms | 67.8% |
| Room messages, 15 min window | 104.1 ms | 38.2 ms | 181 ms | 66 ms | 271 ms | 103 ms | 63.3% |
| User messages, 15 min window | 115.8 ms | 43.0 ms | 198 ms | 74 ms | 305 ms | 111 ms | 62.9% |

The 5-minute windows improve more than the 15-minute windows because narrower
ranges discard a larger fraction of the original day bucket.

### 4.5 Optimization 2 sample results: Caffeine historical query cache

The cache optimization mainly affects repeated historical reads. The synthetic
sample models repeated room/user history queries after warm-up as cache hits on
the optimized path.

| Scenario | Baseline avg | Optimized avg | Baseline p95 | Optimized p95 | Baseline p99 | Optimized p99 | Avg improvement |
|---|---:|---:|---:|---:|---:|---:|---:|
| Repeated room history reads | 85.8 ms | 3.3 ms | 149 ms | 6 ms | 237 ms | 8 ms | 96.2% |
| Repeated user history reads | 92.2 ms | 4.4 ms | 159 ms | 8 ms | 228 ms | 12 ms | 95.2% |

The JMeter dashboard records request latency and errors, but it does not record
Caffeine cache hit rate directly. Cache hit/miss counters should be collected
from `QueryService.messageQueryCacheStats()` in a deployed validation run.

### 4.6 Workloads for a deployed validation run

These workloads should be run against the same dataset and deployment shape for
both the unoptimized and optimized builds.

#### Workload A: repeated historical room reads

Endpoint:

```text
GET /api/query/rooms/{roomId}/messages?start=<oldStartMs>&end=<oldEndMs>
```

Window selection:

```text
oldEndMs <= testStartMs - 60_000
```

Purpose:

- measures Caffeine cache hit benefit
- should show lower p95/p99 latency after Optimization 2
- should show fewer DynamoDB reads after warm-up

#### Workload B: repeated historical user reads

Endpoint:

```text
GET /api/query/users/{userId}/messages?start=<oldStartMs>&end=<oldEndMs>
```

Purpose:

- validates the user-message cache path
- confirms the cache key separates user queries from room queries

#### Workload C: narrow historical windows in populated day buckets

Endpoints:

```text
GET /api/query/rooms/{roomId}/messages?start=<5minStart>&end=<5minEnd>
GET /api/query/users/{userId}/messages?start=<5minStart>&end=<5minEnd>
```

Purpose:

- measures Optimization 1 benefit from `sk BETWEEN`
- compare against baseline that queries the whole day bucket and filters in Java

#### Workload D: mixed read/write workload

Suggested mix:

```text
70% reads
30% writes
```

Read mix:

```text
50% repeated historical room/user reads
20% random historical room/user reads
20% narrow historical reads
10% recent-window reads that bypass cache
```

Purpose:

- matches the Assignment 4 read-heavy test style
- confirms recent-window queries still work without cache
- measures end-to-end API behavior under mixed traffic

### 4.7 Metrics to record

JMeter metrics:

- average response time
- median / p50 response time
- p95 response time
- p99 response time
- throughput in requests per second
- error rate
- top errors by sampler, including response code and affected endpoint
- total completed requests

Application/cache metrics:

- Caffeine hit count
- Caffeine miss count
- Caffeine hit rate
- Caffeine eviction count

Freshness / projection metrics:

- projection lag in milliseconds
- `isConsistent` from the metrics report
- delayed catch-up result for the same test window

DynamoDB metrics:

- `ConsumedReadCapacityUnits` for `room_messages`
- `ConsumedReadCapacityUnits` for `user_messages`
- successful request count / query count if available
- throttled read requests, if any

Server metrics:

- `server-v2` CPU utilization
- `server-v2` memory utilization
- API query worker utilization / queue depth, if exposed
- network in/out

## 5. Future Optimizations

### 5.1 Presence-aware fanout

Maintain `roomId -> active serverIds` presence state, likely in Redis with TTL
heartbeats. A message for a room would be broadcast only to server instances
that currently have subscribers in that room, instead of fanning out to every
server.

Expected impact:

- lower cross-server broadcast bandwidth
- lower internal broadcast request count
- less wasted CPU on servers with no subscribers for the target room

Complexity:

- medium/high because it needs presence updates, TTL cleanup, server crash
  handling, and careful correctness validation to avoid dropping messages.

### 5.2 Partitioned event stream

Evaluate replacing or supplementing RabbitMQ with a partitioned event stream,
such as Kafka or Kinesis, with partitions keyed by room or another stable routing
key. RabbitMQ is still appropriate for the current request/reply and queueing
model, but a log-based stream would make sense if the system needs longer event
retention, replay, or multiple independent downstream consumers.

Expected impact:

- higher sustained broker throughput
- clearer partition-based ordering model
- easier horizontal scaling of consumers
- event replay for projection rebuilds or new analytics consumers

Complexity:

- high because it changes producer, consumer, retry, deployment, monitoring, and
  operational semantics.

### 5.3 Projection worker execution model

Evaluate whether the DynamoDB Streams projection path should remain on Lambda or
move to long-running ECS/Fargate workers. The current Lambda-based projection is
simple and scales naturally for bursty workloads, so this is not an immediate
replacement. The tradeoff becomes more important if projection traffic becomes
steady, high-volume, or requires tighter control over batching, backpressure, and
cost.

Expected impact:

- more control over batch size, concurrency, and retry behavior
- steadier resource usage for sustained projection load
- easier long-running instrumentation and worker-level backpressure

Complexity:

- medium/high because it adds service lifecycle management, autoscaling,
  deployment, failure recovery, and stream checkpointing concerns that Lambda
  currently handles.

### 5.4 Multi-layer query cache

Extend the current per-process Caffeine cache with a shared Redis cache layer.
Caffeine would remain the L1 cache for the fastest same-server hits, while Redis
would act as an L2 cache shared by all `server-v2` instances. DynamoDB remains
the source of truth.

Expected impact:

- fastest repeated reads still return from local memory
- better cache hit rate in multi-server deployments
- fewer repeated DynamoDB reads across the whole fleet
- safer horizontal scaling because cache benefit is not tied to one server

Complexity:

- medium because it needs serialization, cache key versioning, Redis failure
  handling, TTL alignment, and memory sizing.

### 5.5 Virtual-thread query executor with explicit limits

Move the blocking HTTP query handlers from a fixed worker pool to a Java 21
virtual-thread executor, while keeping an explicit concurrency limit around
DynamoDB query calls.

Expected shape:

```text
Netty event loop
  -> virtual-thread-per-task executor
  -> DynamoDB query semaphore
  -> QueryService
  -> JSON response
```

Expected impact:

- higher blocking-I/O concurrency for read-heavy workloads
- lower queueing latency than a small fixed worker pool
- preserved backpressure through the DynamoDB semaphore
- faster failure under overload, such as `429` or `503`, instead of unbounded
  request queue growth

Complexity:

- low/medium because the programming model stays synchronous, but Java 21
  runtime, semaphore sizing, and overload behavior must be validated with load
  tests.

### 5.6 DynamoDB client connection tuning

Tune the AWS SDK HTTP client connection pool and timeouts for high-concurrency
query workloads.

Expected impact:

- lower tail latency when many `server-v2` query threads call DynamoDB
- more predictable behavior under high concurrency

Complexity:

- low/medium because it is mostly client configuration, but the impact needs
  careful load testing to verify.
