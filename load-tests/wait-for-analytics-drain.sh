#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 6 ]]; then
  echo "Usage: $0 <report_base_url> <window_start_ms> <window_end_ms> [queue_url] [max_wait_seconds] [poll_seconds]" >&2
  exit 1
fi

REPORT_BASE_URL="$1"
WINDOW_START_MS="$2"
WINDOW_END_MS="$3"
QUEUE_URL="${4:-https://sqs.<region>.amazonaws.com/<account-id>/<analytics-queue-name>}"
MAX_WAIT_SECONDS="${5:-300}"
POLL_SECONDS="${6:-10}"
AWS_REGION="${AWS_REGION:-us-west-2}"

elapsed=0
while (( elapsed < MAX_WAIT_SECONDS )); do
  ATTRS=$(aws sqs get-queue-attributes \
    --queue-url "$QUEUE_URL" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --region "$AWS_REGION" \
    --output json)

  visible=$(jq -r '.Attributes.ApproximateNumberOfMessages // "0"' <<<"$ATTRS")
  not_visible=$(jq -r '.Attributes.ApproximateNumberOfMessagesNotVisible // "0"' <<<"$ATTRS")

  echo "queue visible=${visible} not_visible=${not_visible} elapsed=${elapsed}s" >&2

  if [[ "$visible" == "0" && "$not_visible" == "0" ]]; then
    exec curl -sS "${REPORT_BASE_URL}?start=${WINDOW_START_MS}&end=${WINDOW_END_MS}"
  fi

  sleep "$POLL_SECONDS"
  elapsed=$((elapsed + POLL_SECONDS))
done

echo "Timed out waiting for analytics queue to drain after ${MAX_WAIT_SECONDS}s" >&2
exit 1
