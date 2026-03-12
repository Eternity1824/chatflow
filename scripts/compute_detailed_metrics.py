#!/usr/bin/env python3
import argparse
import csv
import math
from collections import Counter
from typing import Dict, List


def percentile(sorted_values: List[int], p: float) -> int:
    if not sorted_values:
        return 0
    idx = math.ceil(p * len(sorted_values)) - 1
    idx = max(0, min(len(sorted_values) - 1, idx))
    return sorted_values[idx]


def read_summary(path: str) -> Dict[str, str]:
    summary = {}
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            metric = (row.get("metric") or "").strip()
            value = (row.get("value") or "").strip()
            if metric:
                summary[metric] = value
    return summary


def read_metrics(path: str):
    latencies = []
    room_counts = Counter()
    type_counts = Counter()
    status_counts = Counter()
    total_rows = 0

    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            total_rows += 1
            latency_raw = (row.get("latencyMs") or "").strip()
            if latency_raw != "":
                try:
                    latency = int(latency_raw)
                    if latency >= 0:
                        latencies.append(latency)
                except ValueError:
                    pass

            room_id = (row.get("roomId") or "").strip() or "unknown"
            room_counts[room_id] += 1

            msg_type = (row.get("messageType") or "").strip() or "UNKNOWN"
            type_counts[msg_type] += 1

            status = (row.get("statusCode") or "").strip() or "unknown"
            status_counts[status] += 1

    return {
        "latencies": latencies,
        "room_counts": room_counts,
        "type_counts": type_counts,
        "status_counts": status_counts,
        "total_rows": total_rows,
    }


def format_number(value: float) -> str:
    if isinstance(value, int):
        return f"{value:,}"
    return f"{value:,.2f}"


def parse_int(value: str, default: int = 0) -> int:
    try:
        return int(float(value))
    except ValueError:
        return default


def parse_float(value: str, default: float = 0.0) -> float:
    try:
        return float(value)
    except ValueError:
        return default


def build_markdown(summary: Dict[str, str], metrics: Dict, title: str,
                   metrics_path: str, summary_path: str) -> str:
    latencies = metrics["latencies"]
    latencies.sort()

    count = len(latencies)
    latency_mean = (sum(latencies) / count) if count else 0.0
    latency_min = latencies[0] if count else 0
    latency_max = latencies[-1] if count else 0
    latency_median = percentile(latencies, 0.50)
    latency_p95 = percentile(latencies, 0.95)
    latency_p99 = percentile(latencies, 0.99)

    runtime_ms = parse_int(summary.get("total_runtime_ms", "0"))
    runtime_sec = runtime_ms / 1000.0 if runtime_ms > 0 else 1.0

    lines = []
    lines.append(f"# {title}")
    lines.append("")
    lines.append("**Summary**")
    lines.append(f"- successful_messages: {summary.get('successful_messages', '0')}")
    lines.append(f"- error_responses: {summary.get('error_responses', '0')}")
    lines.append(f"- failed_messages: {summary.get('failed_messages', '0')}")
    lines.append(f"- total_runtime_ms: {summary.get('total_runtime_ms', '0')}")
    lines.append(f"- throughput_msg_per_sec: {summary.get('throughput_msg_per_sec', '0')}")
    lines.append(f"- total_connections: {summary.get('total_connections', '0')}")
    lines.append(f"- reconnections: {summary.get('reconnections', '0')}")
    lines.append("")
    lines.append("**Latency (ms)**")
    lines.append(f"- count: {format_number(count)}")
    lines.append(f"- mean: {format_number(latency_mean)}")
    lines.append(f"- median: {format_number(latency_median)}")
    lines.append(f"- p95: {format_number(latency_p95)}")
    lines.append(f"- p99: {format_number(latency_p99)}")
    lines.append(f"- min: {format_number(latency_min)}")
    lines.append(f"- max: {format_number(latency_max)}")
    lines.append("")
    lines.append("**Throughput Per Room (msg/s)**")
    for room_id in sorted(metrics["room_counts"].keys(), key=lambda x: (len(x), x)):
        count_room = metrics["room_counts"][room_id]
        throughput = count_room / runtime_sec
        lines.append(f"- room {room_id}: {format_number(throughput)} (count={format_number(count_room)})")
    lines.append("")
    lines.append("**Message Type Distribution**")
    total_msgs = sum(metrics["type_counts"].values())
    for msg_type in sorted(metrics["type_counts"].keys()):
        count_type = metrics["type_counts"][msg_type]
        pct = (count_type * 100.0 / total_msgs) if total_msgs else 0.0
        lines.append(f"- {msg_type}: {format_number(count_type)} ({pct:.2f}%)")
    lines.append("")
    lines.append("**Status Code Distribution**")
    total_status = sum(metrics["status_counts"].values())
    for status in sorted(metrics["status_counts"].keys()):
        count_status = metrics["status_counts"][status]
        pct = (count_status * 100.0 / total_status) if total_status else 0.0
        lines.append(f"- {status}: {format_number(count_status)} ({pct:.2f}%)")

    lines.append("")
    lines.append("**Data Sources**")
    lines.append(f"- metrics.csv: {metrics_path}")
    lines.append(f"- summary.csv: {summary_path}")
    lines.append("")
    lines.append("**Notes**")
    lines.append("- Percentiles use the same ceil(p * n) - 1 method as DetailedMetricsCollector.")

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Compute detailed metrics from metrics.csv and summary.csv")
    parser.add_argument("--metrics", default="results/metrics.csv", help="Path to metrics.csv")
    parser.add_argument("--summary", default="results/summary.csv", help="Path to summary.csv")
    parser.add_argument("--title", default="Single-core Metrics (2026-02-07)", help="Title for markdown output")
    parser.add_argument("--out", default="", help="Write markdown output to this file instead of stdout")
    args = parser.parse_args()

    summary = read_summary(args.summary)
    metrics = read_metrics(args.metrics)
    markdown = build_markdown(summary, metrics, args.title, args.metrics, args.summary)

    if args.out:
        with open(args.out, "w") as f:
            f.write(markdown)
    else:
        print(markdown)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
