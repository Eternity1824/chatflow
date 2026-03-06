#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-monitoring/output}"
mkdir -p "$OUT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"

echo "Collecting system metrics to $OUT_DIR at $TS"

if command -v top >/dev/null 2>&1; then
  top -l 1 > "$OUT_DIR/top-$TS.txt" 2>&1 || true
fi

if command -v vm_stat >/dev/null 2>&1; then
  vm_stat > "$OUT_DIR/vm_stat-$TS.txt" 2>&1 || true
fi

if command -v netstat >/dev/null 2>&1; then
  netstat -s > "$OUT_DIR/netstat-$TS.txt" 2>&1 || true
fi

echo "Done"
