#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 <label> <config_path> <websocket_url> [results_dir]" >&2
  exit 1
fi

LABEL="$1"
CONFIG_PATH="$2"
WS_URL="$3"
RESULTS_DIR="${4:-load-tests/results/assignment3}"

mkdir -p "$RESULTS_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_PATH="${RESULTS_DIR}/${STAMP}-${LABEL}.log"

echo "Writing client output to ${LOG_PATH}"
/tmp/client-part2-smoke/client-part2-1.0-SNAPSHOT/bin/client-part2 \
  --config="$CONFIG_PATH" \
  "$WS_URL" | tee "$LOG_PATH"
