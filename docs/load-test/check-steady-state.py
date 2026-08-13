"""Decide whether a JMeter .jtl run ever reached steady state.

    python check-steady-state.py <file.jtl> [file.jtl ...]

A load test whose throughput is still climbing when the run ends was measuring
warm-up, not capacity. Averaging such a run understates it, and comparing it
against a run that started from an already-warm system overstates the delta.
This reports, per file, whether the tail of the run looks settled.

Verdicts:
    STEADY     tail is flat and matches the mid-run reference -> numbers usable
    MARGINAL   small residual drift -> usable with caution
    WARMING    still climbing -> the run never reached capacity
    DEGRADING  throughput fell away from the reference -> something broke
    TOO SHORT  run is not long enough to hold both comparison windows
"""

import csv
import datetime as dt
import sys

BUCKET_S = 60
# Compare the closing window against a mid-run reference window; a run that is
# still improving between the two never settled.
REF_WINDOW = (180, 300)
TAIL_S = 120
WARMING_PCT = 5.0
MARGINAL_PCT = 3.0
# Verdicts read throughput only. These are closed-loop tests with a fixed thread
# count, so latency is just throughput's mirror image; folding both into the rule
# double-counts the same signal and misfires on noise. Latency is still printed.
MIN_WALL_S = REF_WINDOW[1] + TAIL_S


def load(path: str) -> tuple[list[tuple[int, int, bool]], str]:
    """Return (rows, error). rows are (timestamp_ms, elapsed_ms, success)."""
    rows: list[tuple[int, int, bool]] = []
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as f:
            reader = csv.reader(f)
            try:
                header = next(reader)
            except StopIteration:
                return rows, "empty file"
            try:
                i_ts = header.index("timeStamp")
                i_el = header.index("elapsed")
                i_ok = header.index("success")
            except ValueError:
                return rows, "missing timeStamp/elapsed/success column"
            width = len(header)
            for r in reader:
                if len(r) < width:
                    continue
                try:
                    rows.append((int(r[i_ts]), int(r[i_el]), r[i_ok].lower() == "true"))
                except ValueError:
                    continue
    except OSError as exc:
        return [], f"unreadable ({exc.strerror or exc})"
    rows.sort(key=lambda x: x[0])
    return rows, ""


def mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def report(path: str) -> None:
    name = path.replace("\\", "/").split("/")[-1]
    rows, err = load(path)
    if err or not rows:
        print(f"{name:<38} ({err or 'no usable samples'})")
        return

    t0 = rows[0][0]
    # End of run is the last response, not the last request start: a closing
    # stall would otherwise vanish from both the wall clock and the tail window.
    wall = max(ts + el - t0 for ts, el, _ in rows) / 1000.0
    errors = sum(1 for r in rows if not r[2])
    complete = int(wall // BUCKET_S)
    measured = complete * BUCKET_S

    if measured < MIN_WALL_S:
        print(f"{name:<38} start={dt.datetime.fromtimestamp(t0 / 1000):%m-%d %H:%M} wall={wall:.0f}s")
        print(f"  TOO SHORT needs >= {MIN_WALL_S}s to hold a {REF_WINDOW[0]}-{REF_WINDOW[1]}s "
              f"reference plus a {TAIL_S}s tail")
        return

    # Seed every complete bucket so a stall counts as 0 TPS instead of vanishing.
    ok_by_bucket: dict[int, int] = {b: 0 for b in range(complete)}
    lat_by_bucket: dict[int, list[int]] = {b: [] for b in range(complete)}
    for ts, el, ok in rows:
        b = (ts - t0) // (BUCKET_S * 1000)
        if b >= complete:
            continue
        lat_by_bucket[b].append(el)
        # Failed samples are not throughput: a flood of fast errors must not read
        # as healthy capacity.
        if ok:
            ok_by_bucket[b] += 1

    def window(lo: float, hi: float) -> tuple[float, float]:
        """Mean successful throughput and mean latency over [lo, hi) seconds."""
        sel = [b for b in range(complete) if lo <= b * BUCKET_S < hi]
        if not sel:
            return 0.0, 0.0
        tps = mean([ok_by_bucket[b] / BUCKET_S for b in sel])
        lat = mean([mean([float(v) for v in lat_by_bucket[b]]) for b in sel if lat_by_bucket[b]])
        return tps, lat

    ref_tps, ref_lat = window(*REF_WINDOW)
    tail_lo = measured - TAIL_S
    tail_tps, tail_lat = window(tail_lo, measured)

    # Slope inside the tail itself. The window-to-window delta alone cannot tell
    # "climbed early then flattened" from "still climbing", and would read a
    # collapse as agreement.
    tail_buckets = [b for b in range(complete) if tail_lo <= b * BUCKET_S < measured]
    first_tail = ok_by_bucket[tail_buckets[0]] / BUCKET_S
    last_tail = ok_by_bucket[tail_buckets[-1]] / BUCKET_S
    slope_pct = (last_tail - first_tail) / first_tail * 100.0 if first_tail else 0.0

    if ref_tps <= 0:
        verdict, detail = "TOO SHORT", "no successful samples in reference window"
    else:
        d_tps = (tail_tps - ref_tps) / ref_tps * 100.0
        d_lat = (tail_lat - ref_lat) / ref_lat * 100.0 if ref_lat else 0.0
        if d_tps < -WARMING_PCT:
            verdict = "DEGRADING"
        elif d_tps > WARMING_PCT or slope_pct > WARMING_PCT:
            verdict = "WARMING"
        elif d_tps > MARGINAL_PCT or slope_pct > MARGINAL_PCT:
            verdict = "MARGINAL"
        else:
            verdict = "STEADY"
        detail = (f"tail tps {d_tps:+.1f}% vs {REF_WINDOW[0]}-{REF_WINDOW[1]}s, "
                  f"slope within tail {slope_pct:+.1f}% (latency {d_lat:+.1f}%)")

    start = dt.datetime.fromtimestamp(t0 / 1000).strftime("%m-%d %H:%M")
    curve = " ".join(f"{ok_by_bucket[b] / BUCKET_S:.0f}" for b in range(complete))
    overall_avg = mean([float(r[1]) for r in rows])

    print(f"{name:<38} start={start} wall={wall:>5.0f}s n={len(rows):>7} "
          f"avg={overall_avg:>5.0f}ms err={errors}")
    print(f"  {verdict:<9} {detail}")
    print(f"  tps/60s: {curve}")


for arg in sys.argv[1:]:
    report(arg)
