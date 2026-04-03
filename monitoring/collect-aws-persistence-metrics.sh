#!/usr/bin/env bash
# collect-aws-persistence-metrics.sh
#
# Pulls Assignment 3 persistence metrics from AWS CloudWatch via the AWS CLI.
# Outputs CSV lines for DynamoDB, Lambda, and SQS DLQ.
#
# Prerequisites:
#   - AWS CLI v2  (brew install awscli)
#   - aws configure  (or IAM role on EC2)
#   - jq
#
# Usage:
#   NAME_PREFIX=chatflow \
#   AWS_REGION=us-west-2 \
#   PERIOD_SECONDS=60 \
#   START_TIME=2024-01-01T10:00:00Z \
#   END_TIME=2024-01-01T11:00:00Z \
#   ./monitoring/collect-aws-persistence-metrics.sh
#
# If START_TIME/END_TIME are omitted, defaults to the last 30 minutes.

set -euo pipefail

NAME_PREFIX="${NAME_PREFIX:-chatflow}"
AWS_REGION="${AWS_REGION:-us-west-2}"
PERIOD="${PERIOD_SECONDS:-60}"

NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
THIRTY_MIN_AGO=$(date -u -v-30M +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
  || date -u -d "30 minutes ago" +%Y-%m-%dT%H:%M:%SZ)

START_TIME="${START_TIME:-$THIRTY_MIN_AGO}"
END_TIME="${END_TIME:-$NOW}"

# Table / function / queue names (must match Terraform locals)
TABLE_CANONICAL="${NAME_PREFIX}-messages-by-id"
TABLE_ROOM="${NAME_PREFIX}-room-messages"
TABLE_USER="${NAME_PREFIX}-user-messages"
TABLE_USER_ROOMS="${NAME_PREFIX}-user-rooms"
LAMBDA_NAME="${NAME_PREFIX}-cdc-projector"
LAMBDA_ANALYTICS_NAME="${NAME_PREFIX}-redis-analytics"
DLQ_NAME="${NAME_PREFIX}-consumer-v3-dlq"
ANALYTICS_QUEUE_NAME="${NAME_PREFIX}-cdc-analytics-queue"

cw_stat() {
  # $1=namespace $2=metric $3=dim-name $4=dim-value $5=stat
  aws cloudwatch get-metric-statistics \
    --region "$AWS_REGION" \
    --namespace "$1" \
    --metric-name "$2" \
    --dimensions "Name=$3,Value=$4" \
    --start-time "$START_TIME" \
    --end-time   "$END_TIME" \
    --period     "$PERIOD" \
    --statistics "$5" \
    --output json 2>/dev/null \
  | jq -r ".Datapoints | sort_by(.Timestamp) | .[] | \"$4,$2,\(.Timestamp),\(.$5)\""
}

echo "# AWS Persistence Metrics: ${START_TIME} → ${END_TIME}"
echo "# resource,metric,timestamp,value"

# ── DynamoDB: canonical table ──────────────────────────────────────────────
echo "# --- DynamoDB: $TABLE_CANONICAL ---"
cw_stat "AWS/DynamoDB" "ConsumedWriteCapacityUnits" "TableName" "$TABLE_CANONICAL" "Sum"   || echo "$TABLE_CANONICAL,ConsumedWriteCapacityUnits,N/A,0"
cw_stat "AWS/DynamoDB" "WriteThrottleEvents"        "TableName" "$TABLE_CANONICAL" "Sum"   || echo "$TABLE_CANONICAL,WriteThrottleEvents,N/A,0"
cw_stat "AWS/DynamoDB" "SystemErrors"               "TableName" "$TABLE_CANONICAL" "Sum"   || true

# ── DynamoDB: projection tables ───────────────────────────────────────────
for TBL in "$TABLE_ROOM" "$TABLE_USER" "$TABLE_USER_ROOMS"; do
  echo "# --- DynamoDB: $TBL ---"
  cw_stat "AWS/DynamoDB" "ConsumedWriteCapacityUnits" "TableName" "$TBL" "Sum" || true
  cw_stat "AWS/DynamoDB" "WriteThrottleEvents"        "TableName" "$TBL" "Sum" || true
done

# ── Lambda: CDC projector ──────────────────────────────────────────────────
echo "# --- Lambda: $LAMBDA_NAME ---"
cw_stat "AWS/Lambda" "IteratorAge" "FunctionName" "$LAMBDA_NAME" "Maximum" || true
cw_stat "AWS/Lambda" "Errors"      "FunctionName" "$LAMBDA_NAME" "Sum"     || true
cw_stat "AWS/Lambda" "Duration"    "FunctionName" "$LAMBDA_NAME" "Average" || true
cw_stat "AWS/Lambda" "Invocations" "FunctionName" "$LAMBDA_NAME" "Sum"     || true

# ── Lambda: Redis analytics ────────────────────────────────────────────────
echo "# --- Lambda: $LAMBDA_ANALYTICS_NAME ---"
cw_stat "AWS/Lambda" "Errors"      "FunctionName" "$LAMBDA_ANALYTICS_NAME" "Sum"     || true
cw_stat "AWS/Lambda" "Duration"    "FunctionName" "$LAMBDA_ANALYTICS_NAME" "Average" || true
cw_stat "AWS/Lambda" "Invocations" "FunctionName" "$LAMBDA_ANALYTICS_NAME" "Sum"     || true

# ── SQS: analytics queue depth ─────────────────────────────────────────────
echo "# --- SQS: $ANALYTICS_QUEUE_NAME ---"
ANALYTICS_QUEUE_URL=$(aws sqs get-queue-url --queue-name "$ANALYTICS_QUEUE_NAME" --region "$AWS_REGION" \
  --output text --query 'QueueUrl' 2>/dev/null || echo "")

if [[ -n "$ANALYTICS_QUEUE_URL" ]]; then
  ANALYTICS_DEPTH=$(aws sqs get-queue-attributes \
    --queue-url "$ANALYTICS_QUEUE_URL" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --region "$AWS_REGION" \
    --output json 2>/dev/null)
  echo "${ANALYTICS_QUEUE_NAME},ApproximateNumberOfMessages,${NOW},$(jq -r '.Attributes.ApproximateNumberOfMessages // "0"' <<<"$ANALYTICS_DEPTH")"
  echo "${ANALYTICS_QUEUE_NAME},ApproximateNumberOfMessagesNotVisible,${NOW},$(jq -r '.Attributes.ApproximateNumberOfMessagesNotVisible // "0"' <<<"$ANALYTICS_DEPTH")"
else
  echo "${ANALYTICS_QUEUE_NAME},ApproximateNumberOfMessages,${NOW},QUEUE_NOT_FOUND"
fi

# ── SQS: DLQ depth ────────────────────────────────────────────────────────
echo "# --- SQS DLQ: $DLQ_NAME ---"
DLQ_URL=$(aws sqs get-queue-url --queue-name "$DLQ_NAME" --region "$AWS_REGION" \
  --output text --query 'QueueUrl' 2>/dev/null || echo "")

if [[ -n "$DLQ_URL" ]]; then
  DLQ_DEPTH=$(aws sqs get-queue-attributes \
    --queue-url "$DLQ_URL" \
    --attribute-names ApproximateNumberOfMessages \
    --region "$AWS_REGION" \
    --output text --query 'Attributes.ApproximateNumberOfMessages' 2>/dev/null || echo "0")
  echo "${DLQ_NAME},ApproximateNumberOfMessages,${NOW},${DLQ_DEPTH}"
else
  echo "${DLQ_NAME},ApproximateNumberOfMessages,${NOW},QUEUE_NOT_FOUND"
fi
