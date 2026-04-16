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

This architecture was selected because it already separates the hot WebSocket
message path from the persistence projection and analytics path. The canonical
DynamoDB table provides durable message storage, while projection tables support
room and user query access patterns. Redis is used for analytics queries that
would otherwise require expensive scans or repeated aggregation.

### 1.2 Selection criteria

- Performance: the write path is asynchronous after RabbitMQ publish, and
  analytics are handled outside the server request path.
- Scalability: DynamoDB, RabbitMQ consumers, Lambda projection workers, and
  Redis analytics can be scaled independently.
- Maintainability: each component has a clear responsibility: WebSocket
  ingestion, durable persistence, projection, query serving, and analytics.

## 2. Material Optimizations

### 2.1 Optimization 1: DynamoDB sort-key range pruning for time-range queries

#### Problem

The original `server-v2` query path queried a full day bucket from DynamoDB and
then filtered records by `eventTsMs` in Java:

```text
KeyConditionExpression: pk = :pk
Java filter: startMs <= eventTsMs <= endMs
```

This worked functionally, but it could read many unnecessary items. For example,
a query for a five-minute time range inside a busy room-day partition still had
to fetch records from the whole day bucket before discarding most of them in
application code. For large rooms, active users, or long evaluation runs, this
increases DynamoDB read capacity consumption, query latency, and server-side CPU
work.

#### Change

The query condition now includes the DynamoDB sort key range:

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

Because `sk` begins with the epoch millisecond timestamp, DynamoDB can use the
sort key to prune records outside the requested time window before returning
items to the application. The `#\uFFFF` suffix makes the upper bound inclusive
for all message IDs at the same millisecond timestamp.

#### Implementation details

Changed file:

- `server-v2/src/main/java/com/chatflow/serverv2/QueryService.java`

Main implementation changes:

- `queryByPk(...)` now builds `startSk` and `endSk` for each request.
- DynamoDB `QueryRequest` now uses
  `pk = :pk AND sk BETWEEN :startSk AND :endSk`.
- `scanIndexForward(true)` is kept so each day bucket is read in ascending
  timestamp order.
- Java-side `eventTsMs` filtering is retained as a defensive correctness guard.
- `QueryRequest.limit(remaining)` is passed so each day query stops over-reading
  once the global `MAX_RESULTS` quota is close to being reached.
- `remaining = MAX_RESULTS - results.size()` is tracked across day buckets for
  both room-message and user-message queries.

The existing projection writer already stores records with the matching sort key
format:

- `projection-lambda/src/main/java/com/chatflow/cdc/ProjectionEvent.java`
  uses `eventTsMs + "#" + messageId`.
- Terraform defines both `room_messages` and `user_messages` with `pk` as the
  partition key and `sk` as the range key.

#### Correctness considerations

- The time range remains inclusive: `[startMs, endMs]`.
- The Java-side timestamp guard protects against malformed records or future key
  format mistakes.
- Results are still sorted by `eventTs` before returning to the API caller.
- The optimization does not change the public API response shape.

#### Tradeoffs

- This optimization depends on the projection-table sort key continuing to start
  with `eventTsMs`.
- The `sk` value is stored as a string, so timestamp ordering depends on using
  normal 13-digit epoch millisecond values. This holds for current data because
  the system stores modern epoch-ms timestamps.
- The change improves range query efficiency but does not remove the need to
  query one partition per UTC day touched by the requested window.

#### Tests

Changed test file:

- `server-v2/src/test/java/com/chatflow/serverv2/DayBucketExpanderTest.java`

New tests:

- `startSortKeyFormat()`
- `endSortKeyFormat()`

Validation command:

```bash
./gradlew :server-v2:test
```

Result:

```text
BUILD SUCCESSFUL
9 actionable tasks: 3 executed, 6 up-to-date
```

#### Expected performance impact

Expected impact is highest for narrow time-range queries inside large day
buckets. In those cases, DynamoDB should read and return only the matching sort
key range instead of all records in the partition for that day.

Expected improvements:

- Lower DynamoDB read capacity consumption for room/user history queries.
- Lower API latency for narrow time windows in busy rooms or users.
- Lower server CPU and memory pressure because fewer records are filtered in
  Java.
- More predictable query behavior as room-day partitions grow.

#### Measurement plan

Use JMeter to compare the baseline and optimized builds with the same dataset
and same query mix.

Suggested read workload:

- Endpoint 1: `GET /api/query/rooms/{roomId}/messages?start=&end=`
- Endpoint 2: `GET /api/query/users/{userId}/messages?start=&end=`
- Query windows: 1 minute, 5 minutes, 15 minutes, and 1 hour inside populated
  day buckets.
- Metrics: average latency, p95 latency, p99 latency, throughput, error rate,
  and DynamoDB consumed read capacity.

Performance result placeholder:

| Scenario | Baseline avg latency | Optimized avg latency | Baseline p95 | Optimized p95 | Improvement |
|---|---:|---:|---:|---:|---:|
| Room messages, 5 min window | TODO | TODO | TODO | TODO | TODO |
| User messages, 5 min window | TODO | TODO | TODO | TODO | TODO |
| Room messages, 15 min window | TODO | TODO | TODO | TODO | TODO |
| User messages, 15 min window | TODO | TODO | TODO | TODO | TODO |

## 3. Optimization 2

TODO: Add the second material optimization after implementation.

## 4. Performance Metrics

TODO: Add JMeter baseline and optimized results, including average response time,
p95, p99, throughput, error rate, and resource utilization.

## 5. Future Optimizations

TODO: Add 3-5 additional optimization ideas with expected impact and complexity.
