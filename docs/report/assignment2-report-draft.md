# CS6650 Assignment 2 Report (Draft)

- Course: CS6650 Building Scalable Distributed Systems
- Assignment: Assignment 2 - Adding Message Distribution and Queue Management
- Date: 2026-03-08
- Repository URL: `<REPLACE_WITH_YOUR_GIT_REPO_URL>`

## 1. Git Repository URL

`<REPLACE_WITH_YOUR_GIT_REPO_URL>`

The repository includes the required folders:
- `/server-v2`
- `/consumer`
- `/deployment`
- `/monitoring`

## 2. Architecture Document (Front Part)

### 2.1 System Architecture

The deployed system separates concerns into ingress, queueing, and fan-out layers:

1. Clients connect via WebSocket to AWS ALB.
2. ALB forwards/sticks each connection to one `server-v2` instance.
3. `server-v2` validates incoming chat messages and publishes them to RabbitMQ.
4. RabbitMQ routes messages by room (`room.{roomId}`).
5. `consumer` workers read room queues (push mode), process protobuf payloads, and call each server's internal gRPC broadcast service.
6. Each `server-v2` instance broadcasts to its local in-memory room sessions.

```text
Client Threads
    |
    v
AWS ALB (sticky sessions)
    |
    +----------------------+----------------------+----------------------+
    v                      v                      v
server-v2 #1           server-v2 #2          server-v2 #N
(validate + publish)   (validate + publish)  (validate + publish)
    \                      |                      /
     \_____________________|_____________________/
                       |
                       v
          RabbitMQ topic exchange: chat.exchange
                       |
          routing key: room.{roomId}
                       |
      +----------------+----------------+
      v                                 v
  queue room.1 ...                    queue room.20
      \                                 /
       \_______________________________/
                      |
                      v
              Consumer worker pool
                      |
                      v
      gRPC InternalBroadcast.Broadcast (all servers)
                      |
                      v
        Local room broadcast to WebSocket sessions
```

### 2.2 Message Flow Sequence

#### A. Producer path (client -> queue)

1. Client sends `JOIN/TEXT/LEAVE` to `/chat?roomId=<room>`.
2. `RoomIdExtractorHandler` validates room ID and serves `/health` checks.
3. `WebSocketChatHandlerV2` parses JSON, validates fields, enforces `JOIN` before `TEXT`.
4. Server creates `QueueChatMessage` with:
   - `messageId` (UUID)
   - `roomId`, `userId`, `username`, `message`, `timestamp`, `messageType`
   - `serverId`, `clientIp`
5. `RabbitMqPublisher` converts message to protobuf bytes and publishes to `chat.exchange` with routing key `room.{roomId}`.
6. Publisher confirm is handled with async confirm tracking (configurable sync wait can still be enabled).

#### B. Consumer path (queue -> client broadcast)

1. `ProtobufConsumerWorker` subscribes queues with `basicConsume(..., autoAck=false)` and per-worker `basicQos(prefetch)`.
2. Payload is parsed as protobuf; malformed payloads are ACKed and dropped.
3. Dedup check (`messageId`) avoids duplicate side effects.
4. If missing, room sequence is assigned by `RoomSequenceManager`.
5. Delivery enters per-room pending queue; worker dispatches when `roomMaxInFlight` and `globalMaxInFlight` allow.
6. `GrpcBroadcastClient` sends protobuf message to each server gRPC endpoint.
7. On success: queue message is ACKed.
8. On failure: retry with exponential backoff + jitter; if retries exhausted, `NACK(requeue=false)`.

### 2.3 Queue Topology Design

- Broker: RabbitMQ (durable topic exchange).
- Exchange: `chat.exchange`.
- Routing key convention: `room.{roomId}`.
- Queue model: one queue per room, default range `room.1` to `room.20`.
- Queue durability: enabled.
- Queue controls:
  - `x-message-ttl` default `60000 ms`
  - `x-max-length` default `10000`
- Publisher reliability:
  - persistent message properties
  - mandatory publish flag
  - publisher confirms

This topology isolates room traffic, keeps ordering naturally scoped per queue, and allows independent tuning via queue and consumer parameters.

### 2.4 Consumer Threading Model

- Entry point: `ConsumerApp`.
- Threads: configurable fixed-size worker pool (`CHATFLOW_CONSUMER_THREADS`).
- Queue assignment: round-robin fair split across workers (`RoomAssignment.assignQueues`).
- Worker model:
  - one RabbitMQ connection + channel per worker
  - configurable prefetch (`basicQos`)
  - event-driven callback (`basicConsume`) on Netty EventLoop
  - per-room pending queue + inflight gating
- Shared state (thread-safe):
  - dedup cache (`MessageDeduplicator`)
  - per-room sequence counters (`RoomSequenceManager`)
  - metrics counters (`ConsumerMetrics`)
- Health/observability:
  - `/health` returns `OK`
  - `/metrics` exports JSON counters

Note: scaling is configuration-driven (threads + instance count + room shard assignment), not full autoscaling yet.

### 2.4.2 Client Thread Model Decision (Why no 128+ in final runs)

For this implementation, client send path is Netty event-loop + non-blocking write/flush with a global rate limiter.
Under this model, increasing application sender threads beyond a moderate value did not improve throughput and increased instability.

Observed on March 11, 2026 (single server + single consumer, targetQps=4500):

- 32 threads: ~4366 msg/s, lower error ratio, better E2E p99
- 64 threads: ~4311 msg/s, higher error ratio than 32
- 128 threads: ~4224 msg/s, highest error ratio and worse tail latency

Conclusion:
- keep 64-and-below profiles for final tuning and reporting
- exclude 128/256/512 from final optimization set because extra sender threads add context-switch overhead without throughput gain in this Netty-based path

### 2.4.1 Inflight Strategy (Why `inflight=8`, and why keep `inflight=1`)

We use two operation modes intentionally:

- Throughput mode (`CHATFLOW_ROOM_MAX_INFLIGHT=8`):
  - goal: maximize queue drain rate and overall throughput
  - tradeoff: room-local delivery order may be relaxed under bursty load
  - mitigation: include room sequence in payload and let client reorder by sequence when needed

- Strict-order mode (`CHATFLOW_ROOM_MAX_INFLIGHT=1`):
  - goal: preserve room-local processing order in consumer path
  - tradeoff: lower max throughput due to serialized per-room dispatch
  - use: validation runs and requirement-focused evidence

This dual-mode strategy keeps one profile for peak performance tuning and one profile for strict room-order guarantee.

### 2.5 Load Balancing Configuration

Infrastructure is provisioned with Terraform (`deployment/terraform`):

- ALB listener: HTTP `:80` -> server target group.
- Target group protocol/port: HTTP on `chat_port` (default `8080`).
- Sticky sessions: enabled (`lb_cookie`, default `3600s`).
- Idle timeout: default `120s` (satisfies WebSocket > 60s requirement).
- Health check:
  - path `/health`
  - interval `30s`
  - timeout `5s`
  - healthy threshold `2`
  - unhealthy threshold `3`
- Server fleet size is configurable (`server_count`) for 1/2/4-instance test scenarios.

### 2.6 Failure Handling Strategies

1. Queue publish protection (server side)
- Channel pooling reduces connection churn.
- Circuit breaker opens after consecutive publish failures and rejects requests temporarily.
- Half-open probe allows controlled recovery.

2. Consumer delivery resilience
- At-least-once semantics with manual ACK.
- Retry with bounded exponential backoff and jitter.
- Retry count propagated via message header (`x-chatflow-retry`).

3. Duplicate and ordering controls
- Dedup in consumer (`MessageDeduplicator`) and internal broadcast path (`RecentMessageTracker`).
- Room-local sequencing via `RoomSequenceManager` when sequence is absent.

4. Operational safety
- Health endpoints on server and consumer.
- Periodic metrics logging and `/metrics` endpoint for quick diagnosis.
- Graceful shutdown hooks close workers and servers.

### 2.7 Multi-Consumer Scaling Plan and Terraform Impact

Given the fixed room range (`1..20`), horizontal scaling is done by room sharding instead of random queue competition.

Recommended shard rule:
- `owner = (roomId - 1) % consumer_instance_count`
- consumer instance `i` only consumes rooms where `owner == i`

Benefits:
- predictable load split
- better control of room-local ordering semantics
- simpler bottleneck analysis per shard

Terraform/deployment impact:
- add `consumer_count` (number of consumer EC2 instances)
- pass shard env vars per consumer instance:
  - `CHATFLOW_CONSUMER_INSTANCE_INDEX`
  - `CHATFLOW_CONSUMER_INSTANCE_COUNT`
- for gRPC internal fan-out, ensure security group allows `consumer -> server:9090`

For assignment runs, we plan:
- 1 server scenario -> 1 consumer shard
- 2 server scenario -> 2 consumer shards
- 4 server scenario -> 4 consumer shards

## 3. Test Results Template (Fill During Runs)

### 3.1 Metrics Requirements Checklist (Assignment Alignment)

For each run, ensure the following metrics are captured:

- Client metrics:
  - Total runtime
  - Throughput (messages/second)
  - Connection failures
  - Retry attempts
- Queue metrics:
  - Peak queue depth
  - Average queue depth
  - Consumer rate
  - Producer rate
- System metrics:
  - CPU usage (all instances)
  - Memory usage
  - Network I/O
  - Disk I/O

Additional (recommended) metrics used in this report:

- ACK latency (`mean/median/p95/p99`)
- Broadcast E2E latency (`mean/median/p95/p99`)
- Error responses and parse errors

### 3.2 Test Matrix and Summary Table

| Scenario | Server Count | Consumer Count | Message Count | Client Threads | Throughput (msg/s) | ACK p95 (ms) | ACK p99 (ms) | E2E p95 (ms) | E2E p99 (ms) | Conn Failures | Retry Attempts | Peak Queue Ready | Avg Queue Ready | Notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Single-server baseline | 1 | 4 | 500,000 | 64 | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` |
| Load-balanced (2 servers) | 2 | 4 | 500,000 | 64 | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` |
| Load-balanced (4 servers) | 4 | 4 | 500,000 | 64 | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` |
| Stress (4 servers) | 4 | 4 | 1,000,000 | 64 | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` | `<TBD>` |

### 3.3 Screenshot Checklist

#### A. Single Instance Scenario

- [ ] Client terminal output screenshot (performance summary)
- [ ] RabbitMQ Overview screenshot (queue depth + message rates)
- [ ] RabbitMQ Queues screenshot (all room queues with ready/unacked/rates)
- [ ] EC2 system metrics screenshot(s): CPU/Network/Disk
- [ ] Memory evidence screenshot (`free -m` or `top`)

#### B. 2-Instance Load Balanced Scenario

- [ ] Client terminal output screenshot
- [ ] ALB CloudWatch screenshot (RequestCount, TargetResponseTime, HealthyHostCount)
- [ ] RabbitMQ Overview screenshot
- [ ] RabbitMQ Queues screenshot
- [ ] EC2 system metrics screenshot(s)

#### C. 4-Instance Load Balanced Scenario

- [ ] Client terminal output screenshot
- [ ] ALB CloudWatch screenshot (distribution and latency)
- [ ] RabbitMQ Overview screenshot
- [ ] RabbitMQ Queues screenshot
- [ ] EC2 system metrics screenshot(s)
- [ ] Optional stress run screenshot (1M messages)

### 3.4 Configuration Snapshot Per Run

Fill one block per test run:

- Run ID: `<TBD>`
- Date/Time (PST): `<TBD>`
- Git commit: `<TBD>`
- Server image tag: `<TBD>`
- Consumer image tag: `<TBD>`
- `server_count`: `<TBD>`
- `consumer_count`: `<TBD>`
- `consumer_threads`: `<TBD>`
- `consumer_prefetch`: `<TBD>`
- `room_max_inflight`: `<TBD>`
- `global_max_inflight`: `<TBD>`
- Client config file: `<TBD>`

### 3.5 Queue Profile Assessment

For each run, classify queue profile and explain:

- Profile type: `Stable plateau` / `Sawtooth` / `Unstable`
- Peak ready depth: `<TBD>`
- Drain behavior: `<TBD>`
- Redelivery/duplicate behavior: `<TBD>`
- Bottleneck hypothesis: `<TBD>`

### 3.6 System Utilization Snapshot (Minimal Overhead Approach)

Prometheus is not required for this assignment.  
Use the following low-overhead evidence:

- CloudWatch:
  - ALB: RequestCount, TargetResponseTime, HealthyHostCount
  - EC2: CPUUtilization, NetworkIn/Out, DiskReadBytes/DiskWriteBytes
- On-instance commands (during run):
  - `free -m`
  - `top -b -n 1` (or macOS equivalent when local)
  - optional: `iostat -x 1 5`, `sar -n DEV 1 5`

### 3.7 Improvement Analysis (Fill After All Runs)

- Throughput improvement from 1 -> 2 -> 4 servers: `<TBD>`
- Latency trend as scale increases: `<TBD>`
- Queue depth trend as scale increases: `<TBD>`
- Resource bottleneck shifts (server vs consumer vs rabbit): `<TBD>`
- Final tuned configuration and rationale: `<TBD>`
