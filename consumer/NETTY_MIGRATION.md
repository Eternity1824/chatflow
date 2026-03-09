# Consumer Netty Migration

## Overview
Migrated consumer from traditional `ExecutorService` + `BlockingQueue` architecture to Netty `EventLoopGroup` architecture, matching the server-v2 and client design patterns.

## Architecture Comparison

### Before (Old ConsumerWorker)
```
ConsumerApp
  └─ ExecutorService (FixedThreadPool)
      └─ Multiple ConsumerWorker threads
          └─ BlockingQueue<DeliveryEnvelope> (poll with timeout)
          └─ BlockingQueue<AckAction> (offer with timeout)
          └─ Semaphore.tryAcquire() (blocking with timeout)
          └─ Thread.sleep() for backoff
          └─ Synchronous polling loop
```

**Problems:**
- High thread context switching overhead
- Multiple blocking operations per message
- Inefficient CPU usage with timeout-based polling
- Inconsistent with server/client Netty architecture

### After (New NettyConsumerWorker)
```
ConsumerApp
  └─ EventLoopGroup (Epoll/NIO)
      └─ Multiple EventLoop instances
          └─ RabbitMQ callback → EventLoop.execute()
              └─ Async message processing
              └─ Async broadcast (CompletableFuture)
              └─ Async ack/nack
              └─ EventLoop.schedule() for backoff
```

**Benefits:**
- Zero blocking operations - fully event-driven
- No thread context switching within event loop
- Efficient CPU usage - events trigger processing
- Consistent architecture across server/client/consumer
- Better scalability under high load

## Key Changes

### 1. Dependencies (build.gradle.kts)
```kotlin
// Added Netty dependencies matching server-v2
implementation("io.netty:netty-all:4.2.8.Final")
implementation("io.netty:netty-transport-native-epoll:4.2.8.Final:linux-x86_64")
```

### 2. ConsumerApp.java
- Replaced `ExecutorService` with `EventLoopGroup`
- Auto-detects Epoll availability (Linux optimization)
- Each worker gets one `EventLoop` from the group
- Uses `eventLoopGroup.terminationFuture().sync()` instead of `awaitTermination()`

### 3. NettyConsumerWorker.java (New)
- Constructor accepts `EventLoop` instead of running in own thread
- All RabbitMQ callbacks execute on assigned EventLoop
- Flow control uses `AtomicInteger` instead of `Semaphore.tryAcquire()`
- Retry backoff uses `EventLoop.schedule()` instead of `Thread.sleep()`
- Auto-reconnect uses `EventLoop.schedule()` for retry delays

## Performance Improvements

### Eliminated Blocking Operations
| Old Approach | New Approach |
|-------------|--------------|
| `deliveryQueue.poll(timeout)` | RabbitMQ callback → `EventLoop.execute()` |
| `ackQueue.offer(timeout)` | Direct async processing in EventLoop |
| `Semaphore.tryAcquire(timeout)` | `AtomicInteger` check (non-blocking) |
| `Thread.sleep(backoff)` | `EventLoop.schedule(task, delay)` |

### CPU Efficiency
- **Before**: Threads wake up every `pollIntervalMs` even if no messages
- **After**: EventLoop only processes when RabbitMQ delivers messages

### Memory Efficiency
- **Before**: 2 × `BlockingQueue` per worker (delivery + ack queues)
- **After**: Zero queues - direct event processing

## Configuration
All existing environment variables remain unchanged:
- `CHATFLOW_CONSUMER_THREADS` - now controls EventLoopGroup size
- `CHATFLOW_ROOM_MAX_INFLIGHT` - per-room concurrency limit
- `CHATFLOW_GLOBAL_MAX_INFLIGHT` - global concurrency limit
- All retry, dedup, and broadcast configs unchanged

## Compatibility
- **RabbitMQ integration**: Unchanged (still uses amqp-client)
- **BroadcastClient**: Already async (`CompletableFuture`)
- **Metrics**: Unchanged
- **Health server**: Unchanged
- **Message deduplication**: Unchanged
- **Retry logic**: Unchanged

## Migration Notes
- Old `ConsumerWorker.java` is now unused (can be deleted)
- No configuration changes required
- Drop-in replacement - same behavior, better performance
- Epoll automatically used on Linux for maximum performance

## Testing
Build verification:
```bash
./gradlew :consumer:build -x test
```

Run consumer:
```bash
./gradlew :consumer:run
# or
java -jar consumer/build/libs/consumer-1.0-SNAPSHOT-all.jar
```
