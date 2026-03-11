#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

usage() {
  cat <<USAGE
Usage:
  ./deploy-scenario.sh <plan|apply|destroy> <1|2|4> [extra terraform args]

Examples:
  ./deploy-scenario.sh plan 1
  ./deploy-scenario.sh apply 2 -auto-approve
  ./deploy-scenario.sh destroy 4 -auto-approve

Notes:
  - Base values are loaded from terraform.tfvars.
  - Scenario overrides come from scenarios/scenario-<n>.tfvars.
USAGE
}

if [[ $# -lt 2 ]]; then
  usage
  exit 1
fi

action="$1"
scenario="$2"
shift 2

case "$action" in
  plan|apply|destroy)
    ;;
  *)
    echo "Invalid action: $action"
    usage
    exit 1
    ;;
esac

case "$scenario" in
  1|2|4)
    scenario_file="$SCRIPT_DIR/scenarios/scenario-${scenario}.tfvars"
    ;;
  *)
    echo "Invalid scenario: $scenario"
    usage
    exit 1
    ;;
esac

if [[ ! -f "$scenario_file" ]]; then
  echo "Missing scenario file: $scenario_file"
  exit 1
fi

echo "[deploy-scenario] action=$action scenario=$scenario"
echo "[deploy-scenario] using: terraform.tfvars + $(basename "$scenario_file")"

tf_cmd=(terraform "$action" "-var-file=$scenario_file")
if [[ $# -gt 0 ]]; then
  tf_cmd+=("$@")
fi

"${tf_cmd[@]}"
