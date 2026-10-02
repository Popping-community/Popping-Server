# Per-change load verification: predeclared protocol (2026-09-29)

## Purpose and boundary

Measure the cost of recent correctness changes using the preserved JMeter
workload, not the earlier 500-RPS Python probe. No production rollout, push,
original database write, or original container mutation is authorized by this
experiment. Intermediate source variants are disposable benchmark artifacts;
the working tree remains on its current version. This is a new controlled
comparison, not a reproduction of historical absolute performance numbers.

## Frozen workload and common environment

- Use `.tmp/replica-adoption-warm20/prepared/{smoke,warmup,measure}.jmx`.
  Verify the saved warmup/measurement hashes, preserve request order, assertions,
  redirect following, timers, 80/20 selection and CSV contents. Only CSV paths
  are relocated; the already-supported host/port/output properties point to the
  owned fixture. Repository JMX is not interchangeable with the executed file.
- 500 VUsers: groups 300/150/5/5/40; ramp60s; existing random think times.
  Shares are actor-group shares, not request shares. Preserve POST and COMMENT
  reaction scenarios. Do not substitute the previous Python generator.
- One app: 2 CPU / 2 GiB, heap240MiB, code cache64MiB, metaspace256MiB.
  Single Primary: 1 CPU / 1 GiB, buffer pool256MiB, Hikari30, durability1/1.
  Redis sessions OFF, no Replica. An identical HAProxy loopback gateway is used
  for every arm solely to reach the internal app network (0.5 CPU/128MiB).
  This differs from historical two-app Replica runs and direct-connect scaleout
  A; do not compare their published numbers directly with this campaign.
- Restore the existing dump into an owned MySQL only. Validate frozen CSV
  targets against it. Create a clean-shutdown seed snapshot, then clone it into
  a new owned DB volume for each slot. Identical DB initial contents, but writes
  during warmup/measurement and physical caches still evolve. Record this limit.
- JMeter5.6.3 / Java21, 1 GiB client heap; capture client CPU/RAM and host
  available memory as well as app/DB/proxy CPU, GC/JIT/pools/cache and DB reads.
  Existing containers remain running and untouched; shared-host interference
  remains a limitation. Do not build/analyze large files while measuring.

## Source ladder

| Arm | Source | Difference from previous arm |
| --- | --- | --- |
| A | `8d7d963` | Baseline before the pending correctness changes |
| B | A plus removal of Post-detail cache registration | Post detail cache OFF only |
| C | B plus comment TTL5s and sticky cache bypass | Comment cache policy only |
| D | C plus current ViewCountService and view-admission ordering | View-count correction and its observability, assessed together |
| E | `62cc9e5` application source | All remaining current changes, including error/auth/page/delete guards |

Freeze exact patches, source hashes, sanitized JAR hashes and image IDs. D is
not an isolated microbenchmark of merge/remove alone. E-minus-D is a remainder
bundle, not separate attribution to each small guard. The workload does not
exercise comment deletion or invalid-page/auth-negative paths: their correctness
tests, not this traffic, cover those changes. Sequential contrasts can depend
on previous changes; they do not identify all interactions or standalone effects.

## Runs and gates

- Full scope: three blocks, order `A B C D E / E D C B A / C A E B D` (15 slots).
  Every slot uses a fresh cloned DB and fresh app. Record time/order; balancing
  does not eliminate shared-host noise. Adjacent-arm effects require three valid
  observations of both arms. No cherry-picking or success-only replacement runs.
- Each slot: unchanged 30s smoke; fixed1200s warmup; then900s measurement.
  Warmup JIT checks at1080/1200s must both meet the historical <=2% rule, with
  sampling freshness/gaps/resets checked. No adaptive extra warmup or relaxed gate.
  A failed warmup stops the campaign for a recorded decision, not silently retries.
- Analyze configured sampler parents, not parent-plus-redirect-child duplicates.
  Require no failed parent/child or malformed rows and full-duration coverage.
  Use preserved analyzer: reference[180,300), tail[780,900), two60s tail halves,
  STEADY gate and nearest-rank p95/p99. Also check measurement JIT eligibility.
  HTTP/input/ownership/resource failures stop; JIT/steady-only measurement failure
  is preserved as excluded and the declared slot order continues. Fewer than
  three valid measurements means comparison unestablished.
- Report all trials, exclusions, medians/ranges, throughput and latency, CPU,
  DB reads, cache work and memory. Do not reuse the Python probe's p99<=1s/500RPS
  pass gate or label 500 VUsers as a fixed RPS rate. No significance claim from
  three runs and no predefined production SLO.
- Avoid owned app execution across midnight/04:00 scheduled jobs. Wait with no
  owned app active when a whole slot plus cleanup margin cannot fit. Bound each
  JMeter pass by its duration+180s; only terminate its own identified process tree.
- Resource and identity guards are fail-closed. Keep failed-run raw artifacts.
  Clean only IDs/volumes/networks whose ownership is verified, preserve original
  running IDs, and record cleanup outcome. Never use broad Compose down/prune.

## Grafana evidence collection

- Dedicated owned Prometheus (0.25 CPU/384 MiB) and Grafana (0.5 CPU/512 MiB),
  with a small resource exporter (0.1 CPU/64 MiB), run identically for all arms.
  Scrape interval15s. Histograms are enabled identically; monitoring overhead is
  part of this common configuration, not an isolated business-code cost.
- Preserve raw JMeter files, resource JSONL, fixed input hashes, runtime image/JAR
  identities, exact timestamps and per-panel query data. Each PNG is rendered by
  actual Grafana after the owned app has stopped, so rendering does not compete
  with measured load. A failed capture is not a reason to rerun the measurement.
- Grafana HTTP latency is a server-side histogram estimate over rolling1m;
  JMeter parent p95/p99 uses exact nearest-rank samples in the fixed tail. Server
  request throughput includes redirect hops and is not JMeter parent TPS.
- Attach captures to the corresponding comment-cache and view-count articles
  only with matching run conditions and result/exclusion labels. Post-detail
  cache OFF has no dedicated existing article; placement is awaiting user choice.
- Preflight requires >=3 GiB host available RAM; an observed available value
  below768 MiB aborts owned load. Any cgroup OOM event or container restart also
  aborts. These are resource-safety guards, not performance acceptance gates.

## Status

All five source images built; owned seed restored and all frozen CSV targets
validated (1,050,634 posts; 5,050,516 comments; 7,354,332 reactions). First A warmup
failed the predeclared1080s JIT gate (2.3003% >2%);1200s was1.0509%. No measurement
or B-E run was performed. See [result and capture limitations](change-load-20260929-status.md).
No JMeter capacity result is claimed. Previous
functional regressions and the earlier failed Python probe retain their original
scope; they are not measurements in this campaign.
