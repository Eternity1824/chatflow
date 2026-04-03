#!/usr/bin/env bash
# collect-redis-metrics.sh
#
# Connects to the ElastiCache Redis instance and collects:
#   - Memory usage
#   - Ops per second
#   - Spot-reads of analytics keys
#
# Prerequisites:
#   - redis-cli  (brew install redis / apt install redis-tools)
#   - Must run from within the same VPC, or via SSH tunnel:
#       ssh -L 6379:<elasticache-endpoint>:6379 ec2-user@<bastion-ip>
#
# Usage:
#   REDIS_HOST=my-cluster.abc123.use1.cache.amazonaws.com \
#   REDIS_PORT=6379 \
#   ./monitoring/collect-redis-metrics.sh

set -euo pipefail

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_CLI="redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT}"

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
CURRENT_MIN=$(( $(date -u +%s) / 60 ))
CURRENT_SEC=$(date -u +%s)

echo "# Redis metrics snapshot at ${TS}"
echo "# host=${REDIS_HOST}:${REDIS_PORT}"
echo ""

# ── Server info ───────────────────────────────────────────────────────────
echo "=== INFO (memory + stats) ==="
$REDIS_CLI INFO memory | grep -E "used_memory_human|used_memory_peak_human|mem_fragmentation_ratio"
$REDIS_CLI INFO stats  | grep -E "instantaneous_ops_per_sec|total_commands_processed|rejected_connections"
echo ""

# ── Analytics keys spot-read ──────────────────────────────────────────────
echo "=== Analytics keys (current minute bucket: ${CURRENT_MIN}) ==="

# Active users this minute
ACTIVE_USERS=$($REDIS_CLI SCARD "active_users:minute:${CURRENT_MIN}" 2>/dev/null || echo "0")
echo "active_users:minute:${CURRENT_MIN} = ${ACTIVE_USERS}"

# Messages this minute
MSG_MIN=$($REDIS_CLI GET "messages:minute:${CURRENT_MIN}" 2>/dev/null || echo "0")
echo "messages:minute:${CURRENT_MIN} = ${MSG_MIN:-0}"

# Messages this second
MSG_SEC=$($REDIS_CLI GET "messages:second:${CURRENT_SEC}" 2>/dev/null || echo "0")
echo "messages:second:${CURRENT_SEC} = ${MSG_SEC:-0}"

# Top users this minute (top 5)
echo ""
echo "=== Top users this minute ==="
$REDIS_CLI ZREVRANGE "top_users:minute:${CURRENT_MIN}" 0 4 WITHSCORES 2>/dev/null || echo "(key not found)"

# Top rooms this minute (top 5)
echo ""
echo "=== Top rooms this minute ==="
$REDIS_CLI ZREVRANGE "top_rooms:minute:${CURRENT_MIN}" 0 4 WITHSCORES 2>/dev/null || echo "(key not found)"

# Projection health keys
echo ""
echo "=== Projection health ==="
LAST_INGESTED=$($REDIS_CLI GET "projection:lastProjectedIngestedAt" 2>/dev/null || echo "0")
LAST_MSG_ID=$($REDIS_CLI GET "projection:lastProjectedMessageId"   2>/dev/null || echo "")
echo "projection:lastProjectedIngestedAt = ${LAST_INGESTED}"
echo "projection:lastProjectedMessageId  = ${LAST_MSG_ID}"

NOW_MS=$(( $(date -u +%s) * 1000 ))
if [[ "${LAST_INGESTED:-0}" -gt 0 ]]; then
  LAG_MS=$(( NOW_MS - LAST_INGESTED ))
  echo "projection_lag_ms (approx) = ${LAG_MS}"
fi

echo ""
echo "# Done"
