# Single-core Metrics (2026-02-07)

**Summary**
- successful_messages: 4983925
- error_responses: 16075
- failed_messages: 0
- total_runtime_ms: 72242
- throughput_msg_per_sec: 68989.30
- total_connections: 20
- reconnections: 0

**Latency (ms)**
- count: 4,983,925
- mean: 19.01
- median: 19
- p95: 24
- p99: 27
- min: 9
- max: 152

**Throughput Per Room (msg/s)**
- room 1: 3,452.12 (count=249,388)
- room 2: 3,461.74 (count=250,083)
- room 3: 3,457.31 (count=249,763)
- room 4: 3,466.71 (count=250,442)
- room 5: 3,455.33 (count=249,620)
- room 6: 3,468.76 (count=250,590)
- room 7: 3,459.75 (count=249,939)
- room 8: 3,464.48 (count=250,281)
- room 9: 3,467.42 (count=250,493)
- room 10: 3,447.41 (count=249,048)
- room 11: 3,454.06 (count=249,528)
- room 12: 3,454.21 (count=249,539)
- room 13: 3,464.87 (count=250,309)
- room 14: 3,455.40 (count=249,625)
- room 15: 3,463.32 (count=250,197)
- room 16: 3,469.63 (count=250,653)
- room 17: 3,458.85 (count=249,874)
- room 18: 3,459.41 (count=249,915)
- room 19: 3,466.11 (count=250,399)
- room 20: 3,464.94 (count=250,314)

**Message Type Distribution**
- JOIN: 475,986 (9.52%)
- LEAVE: 238,850 (4.78%)
- TEXT: 4,269,089 (85.38%)
- UNKNOWN: 16,075 (0.32%)

**Status Code Distribution**
- 200: 4,983,925 (99.68%)
- 400: 16,075 (0.32%)

**Data Sources**
- metrics.csv: results/metrics.csv
- summary.csv: results/summary.csv

**Notes**
- Percentiles use the same ceil(p * n) - 1 method as DetailedMetricsCollector.
