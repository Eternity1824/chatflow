#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <JMETER_HOME> <smoke|baseline|stress|custom-properties-file> [output-dir]" >&2
  exit 1
fi

JMETER_HOME="$1"
SCENARIO="$2"
OUTPUT_ROOT="${3:-load-tests/results/jmeter}"
PLAN="load-tests/jmeter/chatflow-mixed-workload.jmx"

case "$SCENARIO" in
  smoke)
    PROPS="load-tests/jmeter/smoke.properties"
    ;;
  baseline)
    PROPS="load-tests/jmeter/baseline.properties"
    ;;
  stress)
    PROPS="load-tests/jmeter/stress.properties"
    ;;
  *)
    PROPS="$SCENARIO"
    ;;
esac

if [ ! -f "$PLAN" ]; then
  echo "Missing JMeter plan: $PLAN" >&2
  exit 1
fi

if [ ! -f "$PROPS" ]; then
  echo "Missing properties file: $PROPS" >&2
  exit 1
fi

TS="$(date -u +%Y%m%d_%H%M%S)"
RUN_NAME="$(basename "$PROPS" .properties)"
RUN_DIR="$OUTPUT_ROOT/${RUN_NAME}_${TS}"
RESULTS_JTL="$RUN_DIR/results.jtl"
HTML_DIR="$RUN_DIR/html-report"

mkdir -p "$RUN_DIR"

"$JMETER_HOME/bin/jmeter" \
  -n \
  -t "$PLAN" \
  -q "$PROPS" \
  -J websocket.thread.stop.policy=wsClose \
  -l "$RESULTS_JTL" \
  -e \
  -o "$HTML_DIR"

HOST="$(awk -F= '/^host=/{print $2}' "$PROPS" | tail -n 1)"
PORT="$(awk -F= '/^port=/{print $2}' "$PROPS" | tail -n 1)"

if [ -n "${HOST:-}" ] && [ -n "${PORT:-}" ]; then
  curl -s "http://${HOST}:${PORT}/api/metrics/report" > "$RUN_DIR/metrics-report.json" || true
  curl -s "http://${HOST}:${PORT}/api/analytics/summary" > "$RUN_DIR/analytics-summary.json" || true
  curl -s "http://${HOST}:${PORT}/api/query/active-users" > "$RUN_DIR/active-users.json" || true
fi

echo "JMeter run complete."
echo "Results JTL: $RESULTS_JTL"
echo "HTML report: $HTML_DIR"
echo "Artifacts dir: $RUN_DIR"
