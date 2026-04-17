# Assignment 4 JMeter Guide

This folder contains a JMeter package for the `Eternity1824/chatflow` architecture.

It targets the real `server-v2` WebSocket interface in this repo:

- WebSocket writes: `ws://<host>:8080/chat?roomId=<room>`

## What This Plan Does

`chatflow-mixed-workload.jmx` runs a pure message workload loop with:

- `3` WebSocket `TEXT` writes

There are no HTTP query samplers in the loop.

Because this server requires a user to `JOIN` before sending `TEXT`, the plan:

1. opens one WebSocket connection per thread,
2. sends a `JOIN` once,
3. loops on message traffic,
4. closes the WebSocket at the end.

## Included Files

- `chatflow-mixed-workload.jmx`: main JMeter plan
- `chatflow-users.csv`: rotating room/user data
- `smoke.properties`: 5 users for 10 seconds
- `baseline.properties`: 1000 users for 5 minutes
- `stress.properties`: 500 users for 30 minutes
- `run-assignment4-jmeter.sh`: non-GUI runner

## Prerequisites

1. Install Apache JMeter 5.6+.
2. Install Java 8+.
3. Install `WebSocket Samplers by Peter Doornbosch`.

Recommended plugin steps:

1. Put the Plugins Manager jar in `JMETER_HOME/lib/ext/`.
2. Restart JMeter.
3. Open `Options -> Plugins Manager`.
4. Install `WebSocket Samplers by Peter Doornbosch`.

## First Smoke Test

Before the full runs:

1. Start the target `server-v2` stack.
2. Open `chatflow-mixed-workload.jmx` in JMeter GUI.
3. Temporarily reduce the thread group to `5` users for `10` seconds.
4. Confirm:
   - `WS JOIN` succeeds.
   - `WS TEXT` samplers receive `responseType = ACK`.

## Smoke Run

```bash
bash load-tests/jmeter/run-assignment4-jmeter.sh /path/to/apache-jmeter-5.6.3 smoke
```

Default smoke settings:

- 5 concurrent users
- 5 second ramp-up
- 10 second duration

## Baseline Run

```bash
bash load-tests/jmeter/run-assignment4-jmeter.sh /path/to/apache-jmeter-5.6.3 baseline
```

Default baseline settings:

- 1000 concurrent users
- 60 second ramp-up
- 300 second duration

## Stress Run

```bash
bash load-tests/jmeter/run-assignment4-jmeter.sh /path/to/apache-jmeter-5.6.3 stress
```

Default stress settings:

- 500 concurrent users
- 60 second ramp-up
- 1800 second duration

## Running Against AWS Or A Load Balancer

Edit the scenario properties:

```properties
host=your-server-or-lb-dns
port=8080
```

This repo serves both WebSocket and HTTP API traffic from the same listener by default.

## Important WebSocket Note

This architecture sends sender ACKs immediately, but room broadcasts can also arrive on the same socket later.

If a `RequestResponseWebSocketSampler` occasionally captures a broadcast frame instead of the ACK:

1. Add `WebSocket Text Frame Filter` under the thread group in JMeter GUI.
2. Configure it to discard frames containing `"responseType":"BROADCAST"`.

That gives you cleaner write-latency measurements.

## Metrics To Use In The Report

From the JMeter HTML report and `.jtl`, record:

- Average response time
- p95 response time
- p99 response time
- Throughput
- Error rate

From system monitoring, record:

- CPU and memory
- RabbitMQ publish and queue metrics
- Redis memory and ops/s
- DynamoDB request metrics
- Projection lag from your monitoring pipeline (CloudWatch / consumer logs / queue depth)

## Suggested Report Wording

- Baseline test: `1000 concurrent users, 5 minutes, WebSocket message workload`
- Stress test: `500 concurrent users, 30 minutes, sustained WebSocket message workload`
- Tooling: `Apache JMeter with WebSocket Samplers by Peter Doornbosch`
- Write path: `WebSocket /chat?roomId=<room>`
- Read path: `N/A in this JMeter plan (write-focused WebSocket test)`

## Before And After Comparison

Use the exact same JMeter plan before and after the team’s two optimizations.

For latency improvements:

```text
improvement % = ((before - after) / before) * 100
```

For throughput improvements:

```text
improvement % = ((after - before) / before) * 100
```
