# Change comparison v3 — completed

## Current interpretation and editorial disposition — 2026-10-02

All 15 measurements completed; 14 are eligible. Comparison eligibility means
three accepted trials per arm, not a statistically established improvement.

- A->B: no clear throughput or latency difference established by these three
  observations per arm; overlapping ranges and +0.12% median throughput do not
  prove improvement, equivalence, or zero DB cost. This measures Post-detail
  cache OFF on one CPU-saturated app. The earlier sequential20-GET experiment
  observed one additional Post SELECT per request. The ~4% variability in an
  older experiment is not this campaign's calibrated detection limit.
- D->E: throughput and average-latency ranges separate in the six observations,
  but this is a small, shared-host, bundled-change comparison. Retain sequence
  uncertainty and do not attribute its deltas to any individual guard. D p99
  trials are745/755/846ms (median755), so the last846ms trial does not alone
  explain the -8.08% median delta. It remains included as a valid observation.
- B->C and C->D remain unestablished;03-C's WARMING result is preserved.

First A/B/D/E Grafana CPU panels show post-ramp app saturation; their app CPU
means in [780,900) seconds are1.998780/2.001448/2.000967/1.999063 cores.
Each mean uses eight stored15-second query points of the rolling1-minute rate.
This supports saturation near the2-core quota, not CPU headroom or a bottleneck
attribution for an individual code path. Captions distinguish the full15-minute
plots, initial60-second ramp, and the last120-second JMeter aggregation window.

MySQL CPU was separately recomputed for all six eligible A/B trials using
the same JTL t0 and [780,900) window (eight trailing-one-minute rate samples).
Medians of trial means: A0.746838/B0.747159 cores, delta0.000321; ranges overlap.
This aggregate metric does not isolate additional SELECT cost or prove zero
cost/equivalence. Full48 samples and source hashes are stored in
`_workspace/change-load/blog-cpu-editorial-20261002/mysql-cpu-analysis.json`.

Editorial result: moved A->B to a new article grounded in the9/24 Post-cache
consistency reproduction; consolidated D->E in one common article. Existing7
files contain short cross-links instead of duplicate result sections. Each new
article (md/html) has3 inline panels and links to the other arm's first trial.
All97 evidence files and4 excluded-comparison article originals are unchanged.
The before/after copies and current hashes are under
`_workspace/change-load/blog-editorial-20261002/`. The detail-cache md/html were
subsequently updated with the DB CPU observation; their latest backups/hashes
are in `_workspace/change-load/blog-cpu-editorial-20261002/`. The public resume
section moved to `.claude/docs/research/popping-p1-story-note.md`. Prioritize
detail-cache publication preparation and defer standalone D->E publication.
Future P1-prime wording remains conditional, not a completed implementation or
OFF-retention decision. Other13 article files and97 assets are unchanged.
The old watcher status and
15:49 hashes below describe the first publication into local files, not the
latest revision. See [editorial audit and publication checklist](change-load-20261002-editorial-review.md).

## Historical completion and first local blog append

2026-10-02 15:49 KST final audit: campaign-130f8791299a recorded all15 slots,
ended with cleanup_complete/background_verified true and empty supervisor stderr.
All measurements had zero HTTP/input errors. Fourteen measurements were eligible;
03-C was retained but excluded because its measurement verdict was WARMING.
A/B/D/E each have three eligible trials; C has two. Therefore A->B and D->E are
established, while B->C and C->D remain unestablished and were not published.

The blog watcher copied and verified97 evidence files, then its first article
patch failed because a Markdown source had no final newline and the generated
patch concatenated its remove/add records. An explicit recovery preserved all
original hash and user-edit gates, verified the existing evidence directory
without overwriting it, and supported exact partial-resume checks. The later
HTML mismatch was only the canonical final newline added by apply_patch; the
recovery accepted it only after exact content comparison. Four recovery tests
passed. Final audit:7 established-comparison article files exactly match their
generated contents with one campaign marker each;4 skipped article files remain
byte-identical to their backups with no marker; all97 evidence files and the
evidence manifest match the campaign sources. Watcher status is completed.

A->B median changes: TPS +0.12%, average latency -3.15%, p95 -2.15%, p99
-0.95%. D->E: TPS +1.20%, average latency -3.88%, p95 -6.20%, p99 -8.08%.
These are local single-Primary 500-VUser observations of bundled changes, not
fixed-RPS capacity, production SLO, statistical significance or isolated causal
effects. Raw data remains under the campaign root; no rerun, commit, push or
deployment was performed.

2026-10-01 20:18 KST: user requested continuation. Fresh campaign-130f8791299a
started20:17:52, supervisor PID25472/birth1790853472.3203275. Current stage is
restoring_seed; stderr empty. No new measurement result yet. Protocol conditions
are unchanged except the predeclared bounded publication retry below. No old
trial pooling. Preflight free physical RAM4.98GiB; IDE off, original8 running,
development apps1/2 stopped. Inputs/harness/amended protocol frozen by hash.

New local blog watcher PID19448 in `_workspace/change-load/blog-followup-v2/`
is configured for this exact root;7tests pass,11article files backed up/hashed.
It waits until campaign termination and cleanup, then appends only established
3+3 comparisons with actual Grafana evidence. No blog changes yet.
Read live supervisor/state/status and verify process identity before acting.

## First attempt failure and repair

2026-10-01 20:08 KST audit: b1dc24b4dc13 stopped during04-D measurement at
19:49:50, after212.1s, on Windows PermissionError/WinError5 replacing latest.prom.
Supervisor ended19:50:42 and cleanup completed. Actual owned containers/volumes/
network absent; original8 running IDs retained, development apps1/2 still stopped.
A/B/C each have one eligible zero-error STEADY measurement and complete actual
Grafana captures. D warmup passed both JIT gates but its interrupted measurement
is ineligible. E and all repeat blocks unrun. Every comparison remains unestablished.
Blog followup terminated with no_established_comparisons; all11 originals unchanged.

20:13 continuation: native Windows sharing failure reproduced and bounded
publication retry implemented.48harness tests and actual Docker reader/writer
check passed (500publications/1037reads/0errors). See the protocol amendment and
atomic-publication-check/run-e609c6641cc7/report.json. Exact original lock-holder
unknown. Preparing a fresh campaign; this old campaign is never resumed/pooled.

## Historical launch snapshot

2026-10-01 15:26 KST: campaign-b1dc24b4dc13 supervisor PID6496 started with
protocol warm45-v3. Status at15:26:53: restoring_seed; stderr empty. No smoke,
warmup or measurement result yet. This is a launch snapshot, not a live status.

Protocol: [fixed45minute plan](change-load-20261001-v3-plan.md). A–E three times
each in the declared15-slot order,2700s warmup with2580/2700s JIT gates<=2%,
then900s measurement. New seed/identities, hash-verified old source/JAR/images;
no old sample pooling.43 harness tests passed before launch. Supervisor tests
use mocked lifecycle operations; real v3 preflight/load remain pending.

Preflight RAM7.06GiB, preparation Windows commit headroom16.65GiB. Original
development apps1/2 remain stopped; other8 original containers remain running.
Stopped-app identities are checked before setup and each resource sample.
Previous D/E diagnostic18e48ec41a32 completed with zero HTTP/input errors, but
was not a performance comparison. Its results do not count toward these15 slots.

The run needs at least15hours of load plus seed/setup/captures/cron waits. Keep
IntelliJ and original apps OFF. The driver pauses without an active task app
when90minutes clear of midnight/04:00 are unavailable. Failed gates stop and
clean owned resources; no automatic retry. No success or comparison claimed.

Raw state/logs: `_workspace/change-load/2026-09-29/campaign-b1dc24b4dc13/`.
Read supervisor.json, state.json and per-run records before any next action.
No commit/push/deployment or blog edit was performed.
