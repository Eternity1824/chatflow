# CS6650 Assignment 4: System Optimization (Group Assignment)

## Overview

Working in groups of 4 people, you will optimize an existing chat system implementation by selecting the best architecture from your group members and implementing performance improvements. This assignment focuses on practical optimization techniques and performance measurement.

## Assignment Tasks

### Task 1: Architecture Selection

- **Review all group members' Assignment 3 implementations**
- **Select the best performing architecture** as your baseline
- **Document your selection criteria** (performance, scalability, maintainability)

### Task 2: Implement 2 Material Optimizations (20 points)

Choose and implement **2 significant optimizations** from the following options:

#### Database Optimizations

- **Indexing strategy** - Add appropriate indexes for query patterns
- **Connection pooling** - Optimize database connection management
- **Query optimization** - Improve slow queries with better SQL
- **Materialized views** - Pre-compute expensive aggregations

#### Caching Layer

- **Caching** - Cache frequently accessed data
- **Application-level caching** - In-memory caches for hot data
- **Cache invalidation strategy** - Smart cache refresh policies

#### Load Balancing & Scaling

- **Load balancer implementation** - Distribute traffic across instances
- **Horizontal scaling** - Multiple server instances
- **Connection pooling** - WebSocket connection optimization

#### Message Queue Optimization

- **Queue partitioning** - Improve message throughput

### Task 3: Performance Measurement (10 points)

- **JMeter load testing** - Test before and after optimizations
- **Metrics collection** - Response times, throughput, resource usage
- **Performance comparison** - Document improvement percentages

## JMeter Testing Requirements

### JMeter Setup and Usage

#### Installation

1. **Download Apache JMeter 5.6+** from https://jmeter.apache.org/
2. **Install Java 8+** (required for JMeter)
3. **Extract JMeter** to a directory (e.g., `/opt/jmeter` or `C:\jmeter`)
4. **Run JMeter**:
   - GUI Mode: `./bin/jmeter` (for test creation)
   - Non-GUI Mode: `./bin/jmeter -n -t testplan.jmx -l results.jtl` (for actual testing)

#### Basic JMeter Test Plan Structure

```
Test Plan
├── Thread Group (defines number of users, ramp-up, duration)
├── HTTP Request Defaults (base URL, port)
├── HTTP Requests (individual API calls)
├── Listeners (collect and display results)
└── Assertions (validate responses)
```

#### Creating a Simple Test Plan

1. **Add Thread Group**: Right-click Test Plan → Add → Threads → Thread Group
   
   - Number of Threads: 100 (concurrent users)
   - Ramp-up Period: 60 seconds
   - Loop Count: Infinite (for duration-based tests)

2. **Add HTTP Request**: Right-click Thread Group → Add → Sampler → HTTP Request
   
   - Server Name: localhost
   - Port: 8080
   - Path: /api/messages

3. **Add Listeners**: Right-click Thread Group → Add → Listener
   
   - Summary Report (overview metrics)
   - Response Time Graph (latency over time)
   - Aggregate Report (detailed statistics)

#### WebSocket Testing

For WebSocket connections, you need to install the WebSocket plugin:

##### Plugin Installation (Choose One Method)

**Method 1: JMeter Plugins Manager (Recommended)**
1. **Install Plugins Manager**:
   - Download `jmeter-plugins-manager-X.X.jar` from https://jmeter-plugins.org/
   - Place it in JMeter's `lib/ext/` directory
   - Restart JMeter
2. **Install WebSocket Plugin**:
   - In JMeter: Options → Plugins Manager
   - Go to "Available Plugins" tab
   - Search for "WebSocket Samplers by Peter Doornbosch"
   - Check the box and click "Apply Changes and Restart JMeter"

**Method 2: Manual Installation**
1. **Download Plugin Files**:
   - Go to https://github.com/ptrd/jmeter-websocket-samplers
   - Download the latest release `.jar` files
2. **Install Files**:
   - Place `jmeter-websocket-samplers-X.X.jar` in `lib/ext/` directory
   - Place any dependency `.jar` files in `lib/` directory
   - Restart JMeter

##### WebSocket Test Configuration

1. **Add WebSocket Sampler**: Right-click Thread Group → Add → Sampler → WebSocket Sampler
2. **Configure Connection**:
   - Server Name: localhost
   - Port: 8080
   - Path: /chat
   - Protocol: ws (or wss for secure)
3. **Connection Settings**:
   - Connection timeout: 20000ms
   - Response timeout: 6000ms
   - Use existing connection: Check (for multiple messages)
4. **Message Configuration**:
   - Request data: Your JSON message
   - Example: `{"userId": "user123", "message": "Hello", "roomId": "room1"}`

##### WebSocket Test Plan Example
```
Thread Group (100 users)
├── WebSocket Open Connection
│   └── Server: localhost, Port: 8080, Path: /chat
├── Loop Controller (10 iterations)
│   └── WebSocket Single Write Sampler
│       └── Message: {"userId": "${userId}", "message": "Test message ${__counter()}"}
└── WebSocket Close Connection
```

##### Troubleshooting WebSocket Tests
- **Connection refused**: Ensure your WebSocket server is running
- **Plugin not found**: Verify plugin installation and JMeter restart
- **Timeout errors**: Increase connection and response timeouts
- **Message format**: Ensure JSON format matches your server expectations

#### Running Tests

**GUI Mode (for development):**

```bash
./bin/jmeter
```

**Non-GUI Mode (for actual testing):**

```bash
./bin/jmeter -n -t your-test-plan.jmx -l results.jtl -e -o report-folder
```

**Generate HTML Report:**

```bash
./bin/jmeter -g results.jtl -o html-report-folder
```

### Test Scenarios

Create JMeter test plans to measure:

#### Baseline Performance Test

- **Load**: 1000 concurrent users
- **Number of API Calls** - 100K
- **Duration**: 5 minutes
- **Operations**: 70% reads, 30% writes
- **Metrics**: Response time, throughput, error rate

#### Stress Test

- **Load**: 500 concurrent users
- **Number of API Calls:** 200K-500K
- **Duration**: 30 minutes
- **Operations**: Mixed read/write operations
- **Metrics**: System breaking point, resource utilization

### Required Metrics

For each test scenario, collect:

- **Average response time**
- **p99 & p95 response times**
- **Throughput (requests/second)**
- **Error rate percentage**
- **Resource utilization** (CPU, memory, database connections)

## Submission Requirements (35 points total)

### 1. PDF Report (35 points)

#### Optimizations Section (20 points)

- **Architecture selection rationale** (5 points)
- **Optimization 1 implementation** (7.5 points)
  - What was optimized and why (tradeoffs)
  - Implementation details
  - Performance impact measurement
- **Optimization 2 implementation** (7.5 points)
  - What was optimized and why (tradeoffs)
  - Implementation details
  - Performance impact measurement

#### Future Optimizations (5 points)

- **3-5 additional optimization ideas** you would implement given more time
- **Brief explanation** of each optimization's expected impact
- **Implementation complexity assessment**

#### Performance Metrics (10 points)

- **JMeter test results** for baseline and optimized system
- **Performance comparison tables** showing improvement percentages
- **Resource utilization graphs** (CPU, memory, database)
- **Analysis of bottlenecks** identified and resolved

### 2. File Format

- **Single PDF file** containing all sections
- **Maximum 8 pages** (excluding appendices)
- **Include screenshots** of JMeter results and system metrics
- **Appendix** can contain additional graphs/data

## Grading Rubric

### Optimization Implementation (20 points)

- **Technical depth** (10 points) - Complexity and correctness of optimizations
- **Performance impact** (10 points) - Measurable improvement demonstrated

### Future Optimizations (5 points)

- **Feasibility** (3 points) - Realistic and implementable suggestions
- **Impact assessment** (2 points) - Understanding of optimization benefits

### Performance Analysis (10 points)

- **Test methodology** (4 points) - Proper JMeter setup and execution
- **Results presentation** (3 points) - Clear metrics and comparisons
- **Analysis quality** (3 points) - Insightful interpretation of results