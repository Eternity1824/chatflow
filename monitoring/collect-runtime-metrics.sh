#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <output-dir> <duration-seconds> [interval-seconds]" >&2
  exit 1
fi

OUT_DIR="$1"
DURATION_SECONDS="$2"
INTERVAL_SECONDS="${3:-30}"

AWS_REGION="${AWS_REGION:-us-west-2}"
ALB_HOST="${ALB_HOST:-chatflow-alb-955590197.us-west-2.elb.amazonaws.com}"
WINDOW_MS="${WINDOW_MS:-900000}"
RABBIT_HOST="${RABBIT_HOST:-172.31.54.79}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
RABBIT_MONITOR_INSTANCE="${RABBIT_MONITOR_INSTANCE:-i-01ba1e6558fdc730f}"
INSTANCE_IDS="${INSTANCE_IDS:-i-01ba1e6558fdc730f i-082b50ff1ccb425fb i-093ee8235fb5f839d i-0feb9a10c97b0fb1b}"

mkdir -p "$OUT_DIR"

PROJECTION_CSV="$OUT_DIR/projection-health.csv"
RUNTIME_CSV="$OUT_DIR/runtime-container-metrics.csv"
RABBIT_CSV="$OUT_DIR/rabbitmq-overview.csv"

echo "timestamp,projectionLagMs,isConsistent,lastProjectedMessageId,lastProjectedIngestedAt,thresholdMs" > "$PROJECTION_CSV"
echo "timestamp,instance_id,hostname,container_name,cpu_percent,mem_usage_raw,host_mem_used_mb,host_mem_total_mb" > "$RUNTIME_CSV"
echo "timestamp,queue_depth,publish_total,deliver_total,consumers" > "$RABBIT_CSV"

send_ssm() {
  local instance_id="$1"
  local commands_json="$2"
  aws ssm send-command \
    --region "$AWS_REGION" \
    --instance-ids "$instance_id" \
    --document-name AWS-RunShellScript \
    --parameters "commands=${commands_json}" \
    --query 'Command.CommandId' \
    --output text
}

read_ssm() {
  local command_id="$1"
  local instance_id="$2"
  aws ssm get-command-invocation \
    --region "$AWS_REGION" \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --query 'StandardOutputContent' \
    --output text 2>/dev/null || true
}

collect_projection() {
  local now_epoch now_ms start_ms payload
  now_epoch="$(date +%s)"
  now_ms=$((now_epoch * 1000))
  start_ms=$((now_ms - WINDOW_MS))
  payload="$(curl -s --max-time 10 "http://${ALB_HOST}/api/metrics/report?start=${start_ms}&end=${now_ms}&topN=5" || true)"
  if [[ -n "$payload" ]]; then
    echo "$payload" | jq -r --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '[ $ts, (.health.projectionLagMs // -1), (.health.isConsistent // false), (.health.lastProjectedMessageId // ""), (.health.lastProjectedIngestedAt // 0), (.health.thresholdMs // 0) ] | @csv' \
      >> "$PROJECTION_CSV" 2>/dev/null || true
  fi
}

collect_runtime() {
  local ts instance_id command_id output
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for instance_id in $INSTANCE_IDS; do
    command_id="$(send_ssm "$instance_id" '["HOST=$(hostname)","MEM=$(free -m | awk '\''/Mem:/ {print $3\",\"$2}'\'')","docker stats --no-stream --format \"${HOST},{{.Name}},{{.CPUPerc}},{{.MemUsage}},${MEM}\" || true"]')"
    sleep 1
    output="$(read_ssm "$command_id" "$instance_id")"
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      echo "${ts},${instance_id},${line}" >> "$RUNTIME_CSV"
    done <<< "$output"
  done
}

collect_rabbit() {
  local ts command_id output
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  command_id="$(send_ssm "$RABBIT_MONITOR_INSTANCE" "[\"curl -s --max-time 10 -u ${RABBIT_USER}:${RABBIT_PASS} http://${RABBIT_HOST}:15672/api/overview\"]")"
  sleep 1
  output="$(read_ssm "$command_id" "$RABBIT_MONITOR_INSTANCE")"
  if [[ -n "$output" ]]; then
    echo "$output" | jq -r --arg ts "$ts" \
      '[ $ts, (.queue_totals.messages // 0), (.message_stats.publish // 0), (.message_stats.deliver_get // 0), (.object_totals.consumers // 0) ] | @csv' \
      >> "$RABBIT_CSV" 2>/dev/null || true
  fi
}

DEADLINE=$(( $(date +%s) + DURATION_SECONDS ))
while [[ $(date +%s) -lt $DEADLINE ]]; do
  collect_projection || true
  collect_runtime || true
  collect_rabbit || true
  sleep "$INTERVAL_SECONDS"
done
