#!/usr/bin/env python3
import csv
import sys
from pathlib import Path

import matplotlib.pyplot as plt


def main():
    root = Path(__file__).resolve().parents[1]
    csv_path = root / "results" / "throughput_10s.csv"
    out_path = root / "results" / "throughput_10s.png"

    if not csv_path.exists():
        print(f"Missing CSV: {csv_path}")
        return 1

    buckets = []
    throughputs = []

    with csv_path.open() as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                buckets.append(int(row["bucketStartMs"]))
                throughputs.append(float(row["throughput"]))
            except Exception:
                continue

    if not buckets:
        print("No data to plot.")
        return 1

    # Normalize x-axis to seconds since start
    start = min(buckets)
    x = [(b - start) / 1000.0 for b in buckets]

    plt.figure(figsize=(10, 4))
    plt.plot(x, throughputs, marker="o", linewidth=1)
    plt.title("Throughput Over Time (10s Buckets)")
    plt.xlabel("Time (s)")
    plt.ylabel("Messages / second")
    plt.grid(True, linestyle="--", alpha=0.4)
    plt.tight_layout()
    plt.savefig(out_path)
    print(f"Saved: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
