# Database Design Document — ChatFlow Assignment 3

## 1. Database Choice: DynamoDB + Redis

### Why DynamoDB for durable storage

| Criterion | Choice | Rationale |
|-----------|--------|-----------|
| Write throughput | DynamoDB PAY_PER_REQUEST | No upfront capacity planning; scales to burst without pre-warming |
| Schema flexibility | DynamoDB | Message attributes may evolve; schemaless values outside the key |
| Idempotent writes | DynamoDB `ConditionExpression` | `attribute_not_exists(messageId)` prevents duplicate storage at-least-once delivery |
| Streams / CDC | DynamoDB Streams (`NEW_IMAGE`) | Native, no Debezium or Kafka connector needed |
| Operational overhead | DynamoDB (managed) | No cluster to manage vs. Cassandra / Postgres |

**Trade-offs accepted:** Higher per-read cost vs. relational DB; no ad-hoc queries without pre-defined access patterns; eventual-consistency fan-out via the split CDC pipeline (`Lambda A -> SQS -> Lambda B`).

### Why Redis for analytics

Real-time counters (messages/second, active users) require sub-millisecond write latency.
DynamoDB `UpdateItem` at 5 000 msg/s would cost ~5 000 WCU/s on analytics alone.
Redis `INCR` / `SADD` / `ZINCRBY` are O(1) in-memory, zero WCU cost.

Atomic Lua `EVAL` combines dedupe marker + all 5 analytics writes in a single round-trip,
eliminating the "marker written, stats missing" race.

---

## 2. Schema Design

### 2.1 Canonical Table — `messages_by_id`

```
PK: messageId (String, UUID)
Stream: NEW_IMAGE → Lambda A (CDC projector)
```

Every field of a chat message is stored here exactly once.
No sort key — messages are always fetched by ID, not scanned.

### 2.2 Projection Table — `room_messages`

```
PK: pk  = "roomId#YYYYMMDD"   (daily partition per room)
SK: sk  = "eventTsMs#messageId"  (lexicographically time-ordered, unique)
```

Day-bucket partition prevents hot-partition for active rooms; at 5 000 msg/s
into 10 rooms each room partition receives ~500 msg/s, well within DynamoDB limits.

Query: `KeyConditionExpression: pk = :pk AND sk BETWEEN :start AND :end`

### 2.3 Projection Table — `user_messages`

```
PK: pk  = "userId#YYYYMMDD"
SK: sk  = "eventTsMs#messageId"
```

Same day-bucket strategy for user-level queries.

### 2.4 Projection Table — `user_rooms`

```
PK: userId (String)
SK: roomId (String)
Attribute: lastActivityTs (Number, epoch ms)
```

`UpdateItem` with `SET lastActivityTs = :ts WHERE attribute_not_exists(userId) OR lastActivityTs < :ts`
— conditional update advances the timestamp only forward, safe under at-least-once CDC delivery.

### 2.5 Redis Key Schema

| Key pattern                     | Type     | Purpose                              |
|---------------------------------|----------|--------------------------------------|
| `analytics:processed:<msgId>`   | String   | Dedupe marker (TTL = 3600 s)         |
| `active_users:minute:<bucket>`  | Set      | Unique users per minute bucket       |
| `top_users:minute:<bucket>`     | ZSet     | Score = message count                |
| `top_rooms:minute:<bucket>`     | ZSet     | Score = message count                |
| `messages:second:<bucket>`      | String   | Counter per second bucket            |
| `messages:minute:<bucket>`      | String   | Counter per minute bucket            |
| `projection:lastProjectedIngestedAt` | String | Health marker (epoch ms)         |
| `projection:lastProjectedMessageId`  | String | Health marker (last message ID)  |

Minute bucket = `eventTsMs / 60_000`.  All analytics keys use the message's
own `eventTsMs` — not wall clock — so they remain stable under Lambda B retry.

---

## 3. Indexing / Partition Key Strategy

- **`messages_by_id`** — single hash key (`messageId` UUID).
  Write traffic is uniformly distributed across all partitions — no hot key.

- **`room_messages` / `user_messages`** — composite key `entityId#YYYYMMDD`.
  Day bucket limits the maximum key cardinality and prevents a single popular
  room from monopolising a partition across all time.  Time-range queries expand
  `[startMs, endMs]` into day buckets server-side, query each, merge-sort in Java.

- **`user_rooms`** — hash key `userId`, sort key `roomId`.
  Fetches all rooms for a user in a single Query call.

---

## 4. Scaling Considerations

| Concern | Mitigation |
|---------|-----------|
| DynamoDB write hot partition | Day-bucketed PK spreads load; UUID messageId ensures uniform canonical distribution |
| Lambda A concurrency vs. iterator age | Lambda A scales to shard count; increase shard count or parallelization factor if iterator age grows |
| SQS analytics buffering | SQS absorbs short Redis-side bursts so Lambda A does not block on Redis writes |
| Redis memory | Analytics keys expire after 1 hour; only ~1 440 minute-buckets per day alive at once |
| Fan-out lag | Lambda A and Lambda B process asynchronously; reads may see slightly stale projections / analytics until the pipeline catches up |
| DynamoDB PAY_PER_REQUEST burst | Can switch to provisioned + auto-scaling for predictable cost at steady throughput |

---

## 5. Backup and Recovery

| Mechanism | Implementation |
|-----------|---------------|
| **Point-in-time recovery (PITR)** | Enable via `point_in_time_recovery` block in Terraform (not yet in assignment3.tf — add for production) |
| **DynamoDB Streams retention** | 24 hours; Lambda can be rewound by resetting iterator to TRIM_HORIZON |
| **SQS DLQ** | Unrecoverable consumer-v3 writes land here; replay via `aws sqs receive-message` + re-send |
| **Redis persistence** | ElastiCache Redis: enable AOF or RDB snapshots for analytics data if needed; analytics data is reconstructable from DynamoDB if lost |
| **Idempotency** | All writes are idempotent; replaying any subset of Streams records is safe |
