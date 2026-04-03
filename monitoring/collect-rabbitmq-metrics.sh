#!/usr/bin/env bash
# collect-rabbitmq-metrics.sh
#
# Polls the RabbitMQ HTTP management API and prints queue metrics as CSV.
#
# Prerequisites:
#   - curl
#   - jq  (brew install jq / apt install jq)
#   - RabbitMQ management plugin enabled (default port 15672)
#
# Usage:
#   RABBIT_HOST=10.0.1.5 RABBIT_PORT=15672 \
#   RABBIT_USER=guest RABBIT_PASS=guest \
#   RABBIT_VHOST=%2F RABBIT_QUEUE=chat.queue \
#   ./monitoring/collect-rabbitmq-metrics.sh
#
#   # To sample every 10 seconds for 5 minutes:
#   watch -n 10 ./monitoring/collect-rabbitmq-metrics.sh
#
# Output columns (CSV):
#   timestamp, queue_depth, publish_rate, deliver_rate, consumers, memory_bytes

set -euo pipefail

RABBIT_HOST="${RABBIT_HOST:-localhost}"
RABBIT_PORT="${RABBIT_PORT:-15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
RABBIT_VHOST="${RABBIT_VHOST:-%2F}"
RABBIT_QUEUE="${RABBIT_QUEUE:-chat.queue}"

BASE_URL="http://${RABBIT_HOST}:${RABBIT_PORT}"

# ── Print header on first call ────────────────────────────────────────────────
if [[ "${PRINT_HEADER:-true}" == "true" ]]; then
  echo "timestamp,queue_depth,publish_rate_msg_s,deliver_rate_msg_s,consumers,memory_bytes"
fi

# ── Fetch queue stats ─────────────────────────────────────────────────────────
RESPONSE=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
  "${BASE_URL}/api/queues/${RABBIT_VHOST}/${RABBIT_QUEUE}" 2>/dev/null) || {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),ERROR,,,," >&2
  exit 1
}

TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
DEPTH=$(echo "$RESPONSE"        | jq -r '.messages // 0')
PUB_RATE=$(echo "$RESPONSE"     | jq -r '.message_stats.publish_details.rate // 0')
DEL_RATE=$(echo "$RESPONSE"     | jq -r '.message_stats.deliver_get_details.rate // 0')
CONSUMERS=$(echo "$RESPONSE"    | jq -r '.consumers // 0')
MEMORY=$(echo "$RESPONSE"       | jq -r '.memory // 0')

echo "${TS},${DEPTH},${PUB_RATE},${DEL_RATE},${CONSUMERS},${MEMORY}"

# ── Also dump overview (cluster-level) ───────────────────────────────────────
if [[ "${VERBOSE:-false}" == "true" ]]; then
  echo ""
  echo "# Cluster overview:"
  curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
    "${BASE_URL}/api/overview" | jq '{
      queue_totals,
      message_stats: {
        publish: .message_stats.publish,
        deliver_get: .message_stats.deliver_get
      },
      node: .node
    }'
fi
