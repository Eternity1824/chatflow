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

## 3. Next Sections To Fill Later

The following sections are intentionally left for your experiment outputs:

- Test Results (single instance, 2-instance ALB, 4-instance ALB)
- Throughput/latency tables and analysis
- RabbitMQ console screenshots (queue depth and publish/consume rates)
- ALB distribution screenshots/metrics
- Final tuning decisions (thread counts, prefetch, retry settings)
