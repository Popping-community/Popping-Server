# Per-change load verification v2 — fixed 30-minute warmup

Declared before new traffic, 2026-09-30 KST. The user requested continuation of
the change-by-change verification after reading the saved handoff. This new
campaign applies the proposed uniform 30-minute warmup. The failed 20-minute
campaign remains immutable and excluded from this comparison.

The source ladder, workload, 500 VUsers, resource quotas, seed contents, order
`A B C D E / E D C B A / C A E B D`, three observations per arm, measurement
900s and all acceptance/exclusion rules in change-load-20260929-plan.md remain.
Warmup changes to exactly 1800s in all five groups. JIT checks at 1680 and 1800s
must BOTH meet <=2%, with the original freshness/gap/reset checks. No adaptive
extension or success-only replacement. The analyzer accepts 1800s for discarded
warmup diagnostics; measurement reference [180,300), tail [780,900), two tail
halves and STEADY gate are unchanged. Each pass timeout remains duration+180s.

Before every slot: IntelliJ OFF, physical RAM >=3 GiB, actual Windows commit
headroom >=6 GiB. During every pass: abort below 768 MiB physical or 2 GiB commit
headroom, on OOM/restart/identity failure. GetPerformanceInfo CommitTotal and
CommitLimit times PageSize supply system commit accounting, not FreeVirtualMemory.
These are resource guards, not performance acceptance criteria. Disk must have
20 GiB free at preparation. Preserve original running containers.

Require a 75-minute window clear of midnight and 04:00 before setup and again
before warmup. This conservative margin covers bounded setup, monitoring, all
three duration+180s process timeouts and shutdown. Wait with no owned app active
if the next slot cannot fit. Builds and rendering do not overlap measured load.

Reuse prior source/JAR/image artifacts only after exact source and JAR hashes
and image IDs are verified. Runtime JAR hash is checked for every new app. New
campaign UUID, network, volumes, input manifest and clean restored seed; never
revive old state. Freeze the full harness, original/new JMX and analyzer hashes.
Only CSV paths and five warmup durations change in JMX; XML equality verifies
the remainder. Every run checks the frozen input and harness hashes.

After each unchanged 30s smoke, collect three real resource samples 15s apart.
Require both Prometheus jobs UP, current finite CPU/DB/JIT panel queries, and
actual Grafana dashboard provisioning. Evaluate HTTP latency and throughput
at the fixed smoke launcher end_epoch +15s using the unchanged rolling1m
expressions. Require finite latency values AND strictly positive HTTP rate.
The smoke record must belong to this exact run, have completed successfully,
and be no more than180s old before/after resource sampling. Persist the query
responses and explicit HTTP evaluation timestamp. Never search for a passing
timestamp, replace NaN with zero, or retry traffic to obtain a pass.
Launcher exit is not the last HTTP response: a long exit delay or missing
scrapes may still make this fixed window fail. Such failures stop the campaign;
this change does not guarantee traffic coverage for arbitrary delays.
This resolves the prior CRLF exporter failure before warmup. Render actual PNGs
after app shutdown and inspect images before use. No historical sample backfill.

All trials/exclusions remain visible. Fewer than three eligible measurements per
arm means no completed comparison. Blog additions require corresponding valid
comparisons; original text/images remain preserved. No commit/push/deployment.

## 2026-09-30 preflight amendment (new campaigns only; not started)

The first warm30 campaign completed one eligible A measurement, then stopped
before B warmup. B smoke finished successfully; ~49s after the last response,
the latest1m request rate was0 and latency mean/p95/p99 were NaN. Both jobs
were UP and resource/JIT queries were finite. The old45s post-smoke wait plus
15s scrape interval allowed the rate window to contain only unchanged counters.
This timing explanation matches the saved outputs; individual historical
Prometheus scrapes were not preserved, so the exact scrape phase is unknown.

The amendment above fixes the HTTP evaluation time while retaining current
health/resource checks. Original campaign-d238ee50af5c state, results, frozen
harness and protocol remain unchanged. Its A trial is historical evidence;
do not pool it into the next campaign. Source/JAR artifacts are still reused
from the hash-verified campaign-f856516f7666, with a new identity/seed/15 slots.
New campaign preparation freezes this amended plan and changed harness hashes.

User decision: start the new campaign in a NEW SESSION after closing IntelliJ.
Do not initialize/restore/start a new campaign in the current session. Current
verification uses mocked Prometheus responses; real anchored-query preflight
must be checked in the new session before warmup. Warmup1800, measurement900,
JIT1680/1800<=2%, resource quotas and all measurement gates remain unchanged.
