# Assignment 4 Report Draft

## Basic Info

- Course: CS6650
- Assignment: Assignment 4 - System Optimization
- Name: `Lihan Zhou`
- Date: `2026-04-16`
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

## 4. Final Measurement Plan

The final report still needs measured before/after performance numbers. These
measurements should be collected against the same dataset and deployment shape
for baseline and optimized builds.

### 4.1 JMeter workloads to run

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

### 4.2 Metrics to record

JMeter metrics:

- average response time
- median / p50 response time
- p95 response time
- p99 response time
- throughput in requests per second
- error rate
- total completed requests

Application/cache metrics:

- Caffeine hit count
- Caffeine miss count
- Caffeine hit rate
- Caffeine eviction count

DynamoDB metrics:

- `ConsumedReadCapacityUnits` for `room_messages`
- `ConsumedReadCapacityUnits` for `user_messages`
- successful request count / query count if available
- throttled read requests, if any

Server metrics:

- `server-v2` CPU utilization
- `server-v2` memory utilization
- network in/out

### 4.3 Result tables to fill

#### Optimization 1: DynamoDB range query pruning

| Scenario | Baseline avg | Optimized avg | Baseline p95 | Optimized p95 | Baseline p99 | Optimized p99 | Improvement |
|---|---:|---:|---:|---:|---:|---:|---:|
| Room messages, 5 min window | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| User messages, 5 min window | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| Room messages, 15 min window | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| User messages, 15 min window | TODO | TODO | TODO | TODO | TODO | TODO | TODO |

#### Optimization 2: Caffeine historical query cache

| Scenario | Baseline avg | Optimized avg | Baseline p95 | Optimized p95 | Baseline p99 | Optimized p99 | Cache hit rate |
|---|---:|---:|---:|---:|---:|---:|---:|
| Repeated room history reads | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| Repeated user history reads | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| Mixed read workload | TODO | TODO | TODO | TODO | TODO | TODO | TODO |

#### Resource usage

| Metric | Baseline | Optimized | Change |
|---|---:|---:|---:|
| DynamoDB room_messages consumed RCU | TODO | TODO | TODO |
| DynamoDB user_messages consumed RCU | TODO | TODO | TODO |
| server-v2 average CPU | TODO | TODO | TODO |
| server-v2 average memory | TODO | TODO | TODO |
| Error rate | TODO | TODO | TODO |

## 5. Future Optimizations

### 5.1 Presence-aware fanout

Maintain `roomId -> active serverIds` presence state, likely in Redis with TTL
heartbeats. A message for a room would be broadcast only to server instances
that currently have subscribers in that room, instead of faning out to every
server.

Expected impact:

- lower cross-server broadcast bandwidth
- lower internal broadcast request count
- less wasted CPU on servers with no subscribers for the target room

Complexity:

- medium/high because it needs presence updates, TTL cleanup, server crash
  handling, and careful correctness validation to avoid dropping messages.

### 5.2 Kafka partitioned message bus

Replace or supplement RabbitMQ with Kafka topics partitioned by room or another
stable routing key.

Expected impact:

- higher sustained broker throughput
- clearer partition-based ordering model
- easier horizontal scaling of consumers

Complexity:

- high because it changes producer, consumer, retry, deployment, monitoring, and
  operational semantics.

### 5.3 Shared Redis query cache

Move the historical query result cache from per-process memory to Redis so all
`server-v2` instances share cached results.

Expected impact:

- better cache hit rate in multi-server deployments
- fewer repeated DynamoDB reads across the whole fleet

Complexity:

- medium because it needs serialization, cache key versioning, Redis failure
  handling, and memory sizing.

### 5.4 DynamoDB client connection tuning

Tune the AWS SDK HTTP client connection pool and timeouts for high-concurrency
query workloads.

Expected impact:

- lower tail latency when many `server-v2` query threads call DynamoDB
- more predictable behavior under high concurrency

Complexity:

- low/medium because it is mostly client configuration, but the impact needs
  careful load testing to verify.
