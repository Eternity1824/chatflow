# ChatFlow — Database Layer (Assignment 3)

This directory documents the persistence strategy introduced in Assignment 3.

## Overview

Assignment 3 adds durable message storage on top of the existing
RabbitMQ → consumer → broadcast pipeline.  The persistence layer uses:

| Component | Purpose |
|---|---|
| **DynamoDB** `messages_by_id` | Canonical single-writer table; every message is written here exactly once |
| **DynamoDB** `room_messages` | Projection: messages ordered by room + sequence (for room history) |
| **DynamoDB** `user_messages` | Projection: messages ordered by sender (for user history) |
| **DynamoDB** `user_rooms`    | Projection: which rooms a user has participated in |
| **Redis / ElastiCache**      | Analytics counters, leaderboards, hot-path caches |
| **SQS DLQ**                  | Dead-letter queue for unrecoverable write failures |
| **Lambda A (CDC projector)** | Reads DynamoDB Streams from `messages_by_id`, writes DynamoDB projections, and publishes analytics events to SQS |
| **Lambda B (Redis analytics)** | Consumes analytics events from SQS and updates Redis counters / health markers |

## Write path

```
RabbitMQ → consumer-v3 BatchAccumulator
                           ↓ (virtual thread, conditional PutItem)
               DynamoDB  messages_by_id
                           ↓ (DynamoDB Streams)
               Lambda A (CDC projector)
                  ├── room_messages (PutItem / UpdateItem)
                  ├── user_messages (PutItem)
                  ├── user_rooms    (UpdateItem)
                  └── SQS analytics queue
                           ↓
               Lambda B (Redis analytics)
                  └── Redis         (INCR / ZADD / health markers)
```

## Idempotency

- `messages_by_id` PutItem uses `attribute_not_exists(messageId)` as the
  condition expression.  If the condition fails the item already exists and the
  write is safely skipped.
- consumer-v3 acks the RabbitMQ delivery **after** the conditional PutItem
  succeeds (or after the condition fails with "already exists").
- Lambda A is idempotent for projection fan-out: each Stream record carries the
  full new image; projection writes are safe to retry.
- Lambda B is idempotent for analytics fan-out: Redis uses a dedupe marker per
  `messageId`, so SQS redelivery does not over-count analytics.

## Projection lag

- `ingestedAt` on each `messages_by_id` record is the wall-clock time of the
  DynamoDB write.
- Lambda A stamps `projectedAt` on each DynamoDB projection record.
- Lambda B advances the Redis health markers used by `server-v2` to report
  projection consistency.
- Projection lag = `projectedAt − ingestedAt`.  Typical target: < 1 s under
  normal load.

See `schema.md` for full table and key designs.
