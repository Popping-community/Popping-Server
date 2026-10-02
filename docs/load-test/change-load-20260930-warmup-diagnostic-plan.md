# D/E fixed warmup diagnostic — 2026-09-30

The user requested starting the next experiment after the latest JIT-stop handoff.
This is the proposed D/E warmup-only diagnostic. It does not restart or extend
campaign-f58c4833eee2, and it cannot contribute eligible comparison measurements.

Before traffic, declare two fresh fixtures in order D then E, each restored from
the same newly restored and validated seed. Reuse only verified source/JAR/image
artifacts from campaign-f856516f7666. Keep its workload, five thread groups,
500 users, resource quotas, settings, 30-second smoke and actual anchored
Prometheus/Grafana preflight. Both diagnostic passes last exactly 3600 seconds;
only the five scheduler durations change from the existing warmup JMX.

Record resources and cumulative JIT every 15 seconds. After each pass has exited,
use the earliest parent request-start timestamp as zero and evaluate unchanged
JIT growth/freshness/gap/reset checks at 1680, 1800, 2580, 2700, 3480 and 3600s.
The threshold remains <=2%; both endpoints of each 30/45/60-minute pair must
pass. Failed JIT checkpoints are observations, not an early-stop trigger in this
fixed-length diagnostic. Never extend the pass, retry for success, substitute a
later checkpoint, or call the diagnostic a measured comparison. A pass that
fails every JIT checkpoint is still retained as diagnostic evidence.

JMeter nonzero exit, malformed/failed parent or redirect-child rows, insufficient
coverage (<3598s of request starts), resource/identity/OOM failures or missing
required live metrics stop the diagnostic and trigger owned cleanup. HTTP/CSV
checks run after a pass; resource guards apply while it runs. Missing or stale JIT
checkpoint observations explicitly fail that checkpoint. No heavy analysis or
image rendering runs concurrently with JMeter. There is no measurement phase.

IntelliJ must be OFF. The supervisor may wait for the user to save and close it;
it never closes IntelliJ. Before seed/start, require >=3GiB available physical
RAM and >=6GiB actual Windows commit headroom. During traffic retain the existing
768MiB physical / 2GiB commit abort floors; opening IntelliJ also aborts. Check
>=20GiB free disk when preparing. Require a 90-minute window clear of midnight
and 04:00 before each fixture and again immediately before its warmup. Wait with
no owned app active. Supervisor bound is 8 hours; each warmup timeout is 3780s,
seed import timeout remains 3600s. No automatic resume after any failed attempt.

Retain raw JTL, logs, resource JSONL, frozen inputs/harness and all checkpoint
results. Clean only exact identity/label-verified diagnostic resources, preserving
original running container IDs and all historical artifacts. The state retains
protocol=warm30-v2 solely for compatibility with existing ownership/setup checks;
diagnostic_only=true and diagnostic_protocol=warm60-DE-v1 govern this run. The
comparison runner and measurement phase explicitly reject diagnostic fixtures.

After D/E finish, their one-off observations may inform a new uniform warmup for
a separately declared fresh 15-slot comparison. Neither passing checkpoints nor
two arms establish general JVM stability. No automatic 15-slot run, blog edit,
commit, push or deployment is part of this diagnostic launch.

## 2026-10-01 fresh attempt: development apps stopped

After campaign-a59d4490ebc7 stopped on the host available-RAM floor, the user
authorized cleanup of orphaned JetBrains MCP processes, temporary shutdown of
popping-app-1 and popping-app-2, and a fresh diagnostic launch. Both existing
development app containers remain present but stopped throughout this attempt;
the other eight original containers stay running. Record their identities in
the new campaign before starting. Do not restart the development apps as part
of diagnostic cleanup. The driver's original-running baseline therefore has
eight containers. Its existing preservation check does not enforce the two
stopped containers' state; check those separately when reporting the outcome.

This is a newly declared host background condition. Preserve all previous
campaigns, and do not pool their samples or attribute differences to code alone.
The D/E workload, fresh seed/JVMs, 3600-second duration, six JIT checkpoints,
2% threshold, quotas, RAM/commit floors and cron guards remain unchanged.
The user explicitly requested this new attempt; there is no automatic retry.
