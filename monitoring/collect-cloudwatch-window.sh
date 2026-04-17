#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: $0 <output-dir> <start-iso> <end-iso>" >&2
  exit 1
fi

OUT_DIR="$1"
START_TIME="$2"
END_TIME="$3"

AWS_REGION="${AWS_REGION:-us-west-2}"
NAME_PREFIX="${NAME_PREFIX:-chatflow}"
PERIOD_SECONDS="${PERIOD_SECONDS:-60}"
RABBIT_INSTANCE_ID="${RABBIT_INSTANCE_ID:-i-0df4cff4c1191ce05}"
SERVER_1_ID="${SERVER_1_ID:-i-01ba1e6558fdc730f}"
SERVER_2_ID="${SERVER_2_ID:-i-082b50ff1ccb425fb}"
CONSUMER_1_ID="${CONSUMER_1_ID:-i-093ee8235fb5f839d}"
CONSUMER_2_ID="${CONSUMER_2_ID:-i-0feb9a10c97b0fb1b}"
REDIS_CLUSTER_ID="${REDIS_CLUSTER_ID:-chatflow-redis-analytics}"
REDIS_NODE_ID="${REDIS_NODE_ID:-0001}"

mkdir -p "$OUT_DIR"

EC2_CSV="$OUT_DIR/ec2-cpu.csv"
REDIS_CSV="$OUT_DIR/redis-cloudwatch.csv"

cw_stat() {
  local namespace="$1"
  local metric="$2"
  local statistics="$3"
  shift 3
  aws cloudwatch get-metric-statistics \
    --region "$AWS_REGION" \
    --namespace "$namespace" \
    --metric-name "$metric" \
    --start-time "$START_TIME" \
    --end-time "$END_TIME" \
    --period "$PERIOD_SECONDS" \
    --statistics "$statistics" \
    --dimensions "$@" \
    --output json \
  | jq -r --arg stat "$statistics" '.Datapoints | sort_by(.Timestamp) | .[] | [.Timestamp, .[$stat]] | @csv'
}

echo "resource,timestamp,cpu_utilization_avg" > "$EC2_CSV"
for item in \
  "server-1:${SERVER_1_ID}" \
  "server-2:${SERVER_2_ID}" \
  "consumer-1:${CONSUMER_1_ID}" \
  "consumer-2:${CONSUMER_2_ID}" \
  "rabbit:${RABBIT_INSTANCE_ID}"; do
  name="${item%%:*}"
  id="${item##*:}"
  cw_stat "AWS/EC2" "CPUUtilization" "Average" "Name=InstanceId,Value=${id}" \
    | sed "s/^/${name},/" >> "$EC2_CSV" || true
done

echo "metric,timestamp,value" > "$REDIS_CSV"
for metric in BytesUsedForCache CurrConnections CPUUtilization EngineCPUUtilization DatabaseMemoryUsagePercentage; do
  cw_stat "AWS/ElastiCache" "$metric" "Average" "Name=CacheClusterId,Value=${REDIS_CLUSTER_ID}" "Name=CacheNodeId,Value=${REDIS_NODE_ID}" \
    | sed "s/^/${metric},/" >> "$REDIS_CSV" || true
done

START_TIME="$START_TIME" END_TIME="$END_TIME" PERIOD_SECONDS="$PERIOD_SECONDS" \
  NAME_PREFIX="$NAME_PREFIX" AWS_REGION="$AWS_REGION" \
  /Users/xuefengli/26spring/distribution/chatflow-eternity/monitoring/collect-aws-persistence-metrics.sh \
  > "$OUT_DIR/aws-persistence.csv"
