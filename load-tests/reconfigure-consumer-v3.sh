#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <batch_size> <flush_interval_ms> [semaphore_permits]" >&2
  exit 1
fi

BATCH_SIZE="$1"
FLUSH_MS="$2"
SEMAPHORE_PERMITS="${3:-64}"

AWS_REGION="${AWS_REGION:-us-west-2}"
NAME_PREFIX="${NAME_PREFIX:-chatflow-a2}"

INSTANCE_IDS=$(
  aws ec2 describe-instances \
    --region "$AWS_REGION" \
    --filters \
      "Name=tag:Name,Values=${NAME_PREFIX}-consumer-*" \
      "Name=instance-state-name,Values=running" \
    --query 'Reservations[].Instances[].InstanceId' \
    --output text
)

if [[ -z "${INSTANCE_IDS}" ]]; then
  echo "No running consumer instances found for prefix ${NAME_PREFIX}" >&2
  exit 1
fi

read -r -d '' REMOTE_SCRIPT <<EOF || true
set -euo pipefail
IMAGE=\$(sudo docker inspect chatflow-consumer --format '{{.Config.Image}}' 2>/dev/null || true)
if [[ -z "\${IMAGE}" ]]; then
  IMAGE=\$(sudo docker ps -a --filter "name=^/chatflow-consumer$" --format '{{.Image}}' | head -n1)
fi
if [[ -z "\${IMAGE}" ]]; then
  IMAGE="ghcr.io/eternity1824/chatflow-consumer-v3:v7"
fi
sudo sed -i.bak \
  -e 's/^CHATFLOW_V3_BATCH_SIZE=.*/CHATFLOW_V3_BATCH_SIZE=${BATCH_SIZE}/' \
  -e 's/^CHATFLOW_V3_FLUSH_INTERVAL_MS=.*/CHATFLOW_V3_FLUSH_INTERVAL_MS=${FLUSH_MS}/' \
  -e 's/^CHATFLOW_V3_SEMAPHORE_PERMITS=.*/CHATFLOW_V3_SEMAPHORE_PERMITS=${SEMAPHORE_PERMITS}/' \
  /etc/chatflow/consumer.env
sudo docker rm -f chatflow-consumer || true
sudo docker pull "\${IMAGE}" >/dev/null
sudo docker run -d --name chatflow-consumer \
  --restart unless-stopped \
  --env-file /etc/chatflow/consumer.env \
  -p 8090:8090 \
  "\${IMAGE}" >/dev/null
sudo docker ps --filter "name=^/chatflow-consumer$" --format '{{.Names}} {{.Status}} {{.Image}}'
EOF

REMOTE_SCRIPT_B64="$(printf '%s' "$REMOTE_SCRIPT" | base64 | tr -d '\n')"

COMMAND_ID=$(
  aws ssm send-command \
    --region "$AWS_REGION" \
    --document-name "AWS-RunShellScript" \
    --comment "Reconfigure ChatFlow consumer-v3 batch=${BATCH_SIZE} flush=${FLUSH_MS}" \
    --instance-ids ${INSTANCE_IDS} \
    --parameters commands="echo ${REMOTE_SCRIPT_B64} | base64 -d | bash" \
    --query 'Command.CommandId' \
    --output text
)

echo "SSM command sent: ${COMMAND_ID}"
echo "Instances: ${INSTANCE_IDS}"

while true; do
  STATUSES=$(
    aws ssm list-command-invocations \
      --region "$AWS_REGION" \
      --command-id "$COMMAND_ID" \
      --query 'CommandInvocations[].Status' \
      --output text
  )

  if [[ -n "${STATUSES}" ]] && ! grep -Eq 'Pending|InProgress|Delayed' <<<"${STATUSES}"; then
    break
  fi

  sleep 3
done

aws ssm list-command-invocations \
  --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" \
  --details \
  --query 'CommandInvocations[].{InstanceId:InstanceId,Status:Status,Output:CommandPlugins[0].Output}' \
  --output table
