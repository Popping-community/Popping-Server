# Per-change comparison v3: fixed 45-minute warmup

Declared before traffic on 2026-10-01 KST after the user requested starting.
Diagnostic campaign-18e48ec41a32 completed D/E at 3600 seconds each with no
HTTP/input failures. Both failed the 28-minute JIT checkpoint, while both
43/45-minute and 58/60-minute pairs passed. These single observations support
trying a uniform 45-minute warmup; they do not guarantee future stability.
Diagnostic samples and all older failed/partial campaigns remain excluded.

## Fixed protocol

Keep the source ladder, hashes, inputs, quotas and analysis definitions from
change-load-20260929-plan.md and the corrected smoke-anchored monitoring
preflight from change-load-20260930-v2-plan.md. This document supersedes their
warmup durations, cron margins and original-app background conditions only.

- New UUID/network/input manifest and newly restored, verified seed. Reuse
  source/JAR/images from f856516f7666 only after source/JAR hashes/image IDs and
  the dump hash match. Clone the seed and launch a fresh JVM for each slot.
- Order: A B C D E / E D C B A / C A E B D, three observations per arm.
- Each slot: unchanged30s smoke and real monitoring preflight, fixed2700s
  warmup, then900s measurement. Warmup JIT at2580/2700s must BOTH meet <=2%,
  with the existing freshness/gap/reset requirements. No adaptive extension.
- Measurement reference[180,300), tail[780,900), tail halves, STEADY and
  measurement JIT rules are unchanged. Any warmup JIT/HTTP/input/resource
  failure stops the campaign. Measurement JIT/STEADY-only exclusions remain
  visible and do not get replacement trials. Three eligible observations per
  arm are required for each adjacent-arm comparison.
- 500VUsers, five groups300/150/5/5/40, original ramp/think times/request mix.
  App2CPU/2GiB, heap240MiB/code cache64MiB/metaspace256MiB; MySQL1CPU/1GiB,
  buffer256MiB/Hikari30/durability1/1. JMeter heap1GiB unchanged. No fixed-RPS,
  production SLO, maximum-capacity or individual attribution for bundled edits.
- Before seed and each slot: IntelliJ OFF, >=3GiB physical RAM and >=6GiB
  actual Windows commit headroom. Traffic abort floors remain768MiB/2GiB.
  >=20GiB disk at preparation. OOM/restart/identity/input failures abort.
- The user-authorized development-app shutdown remains in effect: original
  popping-app-1 and popping-app-2 must remain stopped with the same IDs. The
  other eight original containers remain running. Freeze the two stopped IDs
  in state.json and verify their state before setup and at each resource sample.
  Do not restart the original apps during cleanup. Earlier MCP orphan cleanup
  also changes host background; historical samples are not directly pooled.
- Require90minutes clear of midnight/04:00 before setup and again immediately
  before warmup (previous75minute margin plus15minutes of extra warmup). Wait
  with no owned app active if the slot cannot fit. Pass timeout=duration+180s.
  Retain the24hour supervisor bound checked between individual slots/waits;
  this is not a hard watchdog during individual stages. The full campaign
  needs at least15hours of load plus seed/setup/captures/cron waits.

## Evidence and termination

Freeze this protocol, the harness and supervisor source/hashes before launch.
Only CSV relocation and five warmup durations change in JMX; verify XML
equivalence for all other fields. Extend the frozen analyzer's accepted-duration
list only; its900s measurement calculations remain byte-for-byte otherwise.
Retain raw JTL/resource JSONL/logs and every exclusion. Capture real Grafana
panels only after the task app stops; inspect images before publishing them.
No compilation or heavy unrelated analysis during traffic.

On success or failure, clean only identity/label-verified owned resources and
verify the eight original running IDs plus two intentionally stopped app IDs.
Never automatically retry, reuse previous measurements or relax a gate.
Blog additions require valid corresponding comparisons and image inspection;
this launch makes no blog changes, commit, push or deployment.

Validation before launch:43 local harness tests pass, including45minute XML
equivalence, synthetic900s analyzer equivalence,2700s coverage,43minute failure
despite45minute success, cron boundaries, stopped-app drift, phase-duration
rejection, no measurement after warmup rejection, and mocked supervisor seed/
15-slot/cleanup behavior. These are harness checks, not real load results.
The implementing agent reviewed the changed execution and cleanup paths; no
independent reviewer or external Claude review is claimed. No Java code changed.

## 2026-10-01 20:13 publication fix for a new campaign

The first v3 campaign b1dc24b4dc13 stopped during04-D measurement when replacing
monitoring/latest.prom raised Windows PermissionError/WinError5. A/B/C each
completed one eligible measurement with actual captures; none establishes a
three-trial comparison. Preserve that campaign unchanged; no resumption/pooling.

Use atomic_io.write_text_atomic for telemetry and JSON state publication. Write
the same payload once to a sibling pending file, then retry only Windows errors
5/32/33 at50ms intervals for at most a2second retry-wait budget. Leave the old
target intact until replacement succeeds. Persistent and unrelated I/O errors
still propagate and stop the campaign. This is file-publication retry, not
traffic replay, metric resampling, warmup extension or a changed acceptance gate.
Telemetry records publication retry count and elapsed time in resources.jsonl.
Sample intervals/freshness/gap limits and all experiment gates remain unchanged.

48 harness tests pass, including native Windows held-reader reproduction of
WinError5, recovery after release, and persistent-lock rejection with both old
and pending files preserved. Isolated Docker exporter check e609c6641cc7:
500publications/1037HTTP reads/0errors,237publication retries, max publication
0.1029s; native-lock recovery0.2548s. Owned check container removed, originals
preserved. This establishes the sharing-conflict mechanism and bounded recovery;
it does not identify the exact reader that held the failed campaign's file.
Evidence: _workspace/change-load/atomic-publication-check/run-e609c6641cc7/report.json.
The same issue is described by the Windows FILE_SHARE_DELETE contract:
https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilea

The user explicitly requested continuation after the recorded stop. Prepare
one fresh UUID/seed15-slot campaign with this amended protocol/harness frozen.
Original apps remain stopped. Reconnect the authorized local blog followup to
the new campaign using a fresh watcher directory and backups; preserve prior
watcher status=no_established_comparisons and its unchanged originals.
