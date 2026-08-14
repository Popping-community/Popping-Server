import csv
import sys
from collections import defaultdict

PATH = sys.argv[1]
WARMUP_S = int(sys.argv[2]) if len(sys.argv) > 2 else 120


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0
    k = int(round(p / 100.0 * (len(sorted_vals) - 1)))
    return sorted_vals[k]


rows = []
with open(PATH, newline="", encoding="utf-8", errors="replace") as f:
    for r in csv.DictReader(f):
        try:
            rows.append((int(r["timeStamp"]), int(r["elapsed"]), r["label"], r["success"]))
        except (ValueError, KeyError, TypeError):
            continue

rows.sort(key=lambda x: x[0])
t0, tend = rows[0][0], rows[-1][0]


def summarize(name, subset):
    if not subset:
        return
    el = sorted(x[1] for x in subset)
    span = (subset[-1][0] - subset[0][0]) / 1000.0
    errs = sum(1 for x in subset if x[3].lower() != "true")
    print(
        f"{name:<38} n={len(subset):>7} dur={span:>7.1f}s tps={len(subset)/span:>6.1f} "
        f"avg={sum(el)/len(el):>7.0f} p50={pct(el,50):>6} p90={pct(el,90):>6} "
        f"p95={pct(el,95):>6} p99={pct(el,99):>6} max={el[-1]:>6} err={errs}"
    )


print(f"file={PATH}  total_samples={len(rows)}  wall={(tend-t0)/1000.0:.1f}s\n")
summarize("OVERALL (incl. ramp)", rows)

steady = [x for x in rows if x[0] >= t0 + WARMUP_S * 1000]
summarize(f"STEADY (excl. first {WARMUP_S}s)", steady)

print("\n--- per endpoint (steady state) ---")
by_label = defaultdict(list)
for x in steady:
    by_label[x[2]].append(x)
for label in sorted(by_label, key=lambda k: -sum(y[1] for y in by_label[k]) / len(by_label[k])):
    summarize(label[:38], by_label[label])
