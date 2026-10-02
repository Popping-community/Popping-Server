# Deployment follow-up and pending-work closeout (2026-09-29)

## Scope

This follow-up closes the existing local changes, checks a warm single application
against a declared load, and connects the CI-built image to the deployment image.
No registry push, Git push, SSH connection or remote rollout was executed locally.
Readiness/quiescence/retention controllers remain local experiments: the CI script
below is image handoff, **not a readiness-gated rolling deployment**.

## Fresh regression evidence

- Java: **272 tests, zero failures/errors/skips**, fresh isolated MySQL/Redis,
  `--rerun-tasks --no-daemon --max-workers=1`, Java 21. XML totals were recounted.
- Deployment tools: **57 tests passed**. The new shell tests execute fake Docker
  commands, covering exact digest propagation, missing Compose/service, invalid
  digest, failed pull/up, wrong runtime image/reference and stopped containers.
- Workflow YAML parsed; shell syntax and `git diff --check` passed.
- The actual local Compose parser merged a synthetic digest override into both
  app slots. This config-only check did not pull or start any application.
- The regression run removed its two owned dependency containers and preserved
  the original ten running IDs. Current Java source hashes match the tested input.

Private raw evidence is retained locally under
`_workspace/deployment-finish/2026-09-29/regression-dfc344bf48/` (not committed).
Historical reports keep their original dates and limitations; new commits do not
turn their old results into new runtime tests.

## Image handoff

The main-push workflow now reads `build/jib-image.digest` after a successful Jib
push and passes `chooh1010/popping-community@sha256:...` to the SSH action.
The readable tag is `git-<commit>`, but deployment uses the immutable reference,
not a mutable tag. Release reference, registry digest and local image ID are
preserved as workflow artifacts. Jib documents the distinction in its
[additional build artifacts](https://github.com/GoogleContainerTools/jib/blob/master/jib-gradle-plugin/README.md#additional-build-artifacts).

`deploy_digest.sh` requires both existing `app-1` and `app-2` services before
registry login/pull/recreation. It overlays only their image fields, pulls them,
then uses `up -d --no-deps app-1 app-2`. It checks each resulting container's
image configuration ID, configured digest reference and running state. Credentials
are passed via environment and password-stdin, not shell source interpolation or
the Docker login argument list. The local tests do not contact a registry or SSH.

The generated override is retained, including on partial failure, because Compose
stores its path in container labels. Use the recorded base/override pair for later
Compose operations. An unqualified `docker-compose up` still uses the old image
declared in the base file; it is **not** the supported release path. No automatic
rollback or override pruning is implemented.

Workflow concurrency serializes runs for one ref and does not cancel an active
deployment. It is not a host lock against manual or other controllers. Remote
working directory, Compose version, application readiness, runtime configuration,
secret delivery and capacity remain unverified until a separately authorized real
CI/remote run. A running container alone is not business readiness. The two apps
can be recreated together; this script does not claim zero downtime.

## Single-app capacity protocol (declared before load)

The historical 500-VUser figures used older code and a closed-loop JMeter plan.
The current post-detail cache is default OFF. This is a fresh acceptance experiment,
not a before/after improvement percentage or an exact replay of those VUsers.

- Fresh sanitized current-source boot JAR, embedded in an exact local image ID;
  runtime JAR SHA-256 checked. This local fixture is **not the CI Jib artifact**.
- Existing large SQL dump restored only to a new disposable MySQL. No original
  DB mutation. App/MySQL/Redis are on an internal network; only an owned HAProxy
  exposes an automatically allocated loopback port.
- Measurement resources: app 2 CPU / 2 GiB / max heap 240 MiB; MySQL 1 CPU / 1 GiB
  / 256 MiB buffer pool; Redis 0.5 CPU / 256 MiB; HAProxy 0.5 CPU / 128 MiB.
  Final fixture uses these DB limits from initial creation, without live memory
  shrinking after restore. Primary only,
  one Hikari pool of 50, Redis sessions ON; no Replica freshness claim.
- Target **500 scheduled HTTP requests/s**, no retry, bounded 256 client workers.
  Requests not issued because that bound is reached count as failures. Latency
  includes time from the planned arrival, not just socket time.
- Request shares: guest reads 60%, member reads 30%, guest/member post/comment
  writes 0.5% each, guest post-reaction add/remove 4% each. Read routes rotate through
  board list, board, post and comments; hot/normal data share is 80/20. These are
  request shares, not legacy thread shares. Twenty authenticated and twenty guest
  identities are initialized outside measurement. No browser assets or WebSocket
  traffic is generated. Write redirects are checked without fetching the child GET.
- Smoke first; discard a 600-second warmup at target. Then require three consecutive
  180-second measurements. Any failed measurement rejects the declared target;
  do not lower the threshold after observing results or relabel a smaller workload.
- HTTP acceptance: zero status/business-check/client-drop failures, all scheduled
  requests recorded, each 30-second window and each route's full-run p99 <= 1s,
  client dispatch-lag p99 <= 50ms, unchanged app identity and authenticated session.
  These are lab acceptance thresholds, not an established production SLO.
- Record app/DB/proxy cgroup CPU/throttling and JVM compilation/connection-wait
  observations. HTTP acceptance alone does not establish resource headroom,
  steady JVM compilation, deployment overlap capacity or a production HA guarantee.

### Warm-up eligibility and bounded follow-up

The first warmup did not establish a warm JVM. Before continuing, one additional
600-second warmup and one 180-second observation were selected as a bounded
follow-up, without changing the image, resources, client limit or HTTP thresholds.
The existing `run-ab.sh` uses <= 2% cumulative compilation-time growth in its
final approximately 120 seconds as a warm-up indicator. Its denominator is the
**last** cumulative value; the independent audit also reports growth relative to
the first value, explicitly named. For the first warmup these are 17.90% and
21.81%, respectively, not contradictory results. Neither indicates settled JIT.
The extra observation cannot certify warm capacity if that eligibility check
still fails. Failed or ineligible observations are not followed by success-only
retries; the original three-success acceptance requirement remains unmet.

The client records unissued arrivals with zero timing placeholders. Therefore
the result table uses **issued-request** arrival p99, not a percentile diluted
by those placeholders. Unissued arrivals still fail the HTTP acceptance gate;
they are not HTTP 5xx responses. The 256-worker limit may constrain the offered
rate, and drops also alter the delivered route mix. This experiment cannot
determine maximum server throughput or isolate one application bottleneck.

### Result: target not certified; no warm-capacity claim

| Run | Duration | Scheduled | Issued | Not issued | Issued arrival p99 | Issued business/status failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Smoke, 10 RPS | 20s | 200 | 200 | 0 | 28.72ms | 0 |
| Warmup 1, discarded | 600s | 300,000 | 173,703 | 126,297 | 3,110.16ms | 0 |
| Warmup 2, discarded | 600s | 300,000 | 215,901 | 84,099 | 2,080.54ms | 0 |
| Final observation, 500 offered RPS | 180s | 90,000 | 77,190 | 12,810 | 1,690.19ms | 0 |

The smoke passed. The final observation failed HTTP acceptance: 14.23% of
scheduled arrivals were not issued, every 30-second window exceeded the p99
threshold, and several routes also exceeded it. The worst full-run route p99
was guest post creation at 2,463.43ms. The 77,190 issued responses comprised
76,434 HTTP200 and 756 expected redirects, all passing the runner's business
checks. Dispatch-lag p99 was 9.32ms. App identity and the authenticated-session
post-checks held; no sampling errors occurred. CSV recounts matched all summaries.

The final approximately 113-second JIT tail grew **2.40%** relative to its last
cumulative value (2.46% relative to its first). This still misses the 2% warm-up
indicator, after the second warmup's 5.47%. Thus the experiment is closed as
**failed acceptance / steady-state capacity unestablished**, not as a stable
maximum-capacity measurement or proof that a fully warm server can never meet
the target. Three successful repetitions were not achieved; no further repeats
were run. The declining latencies during warmup are not a code improvement.

Across the final CPU samples, app consumption averaged **2.004 cores** against
a 2-core quota, MySQL 0.577 and proxy 0.263. App throttling occurred in 99.94%
of quota periods. That is a fraction of periods, **not** a percentage of elapsed
request time. It supports app CPU quota saturation in this fixture, not a proven
root cause inside application code. No load-generator process CPU/maximum-rate
calibration or controlled resource A/B was done.

Machine-readable [aggregate evidence](deployment-finish-20260929-evidence.json)
includes raw CSV/summary SHA-256, per-route/window results, exact local image and
runtime JAR identity, CPU observations and explicit JIT denominators. Raw logs,
CSV, audit output and executed runner snapshots remain in the private directory
`_workspace/deployment-finish/2026-09-29/capacity-1307be3be96b/`.
All 103 selected fixture source hashes still matched at closeout; all 130 saved
regression source hashes also matched. The initial smoke's completed 200 requests
succeeded but its post-check hit an idle connection; that raw run is preserved,
and the corrected runner closes idle connections before new operations, without
retrying requests. Only `smoke-v2` has the complete acceptance/post-check result.

The four owned fixture containers and their disposable volumes plus the owned
network were removed after log capture. Original ten running container IDs were
preserved, with no owned containers left and no cleanup errors. The dump, local
images and evidence remain. No Git/registry push, SSH or remote deployment took
place. Further CPU/heap/DB or generator tuning is a new experiment, not silently
included in this closeout.

Preparation failures are preserved, not counted as load results:
local image-ID vs registry-digest build input; occupied fixed port
(an existing process was left untouched); absent published port on the internal
Docker network. The revised fixture exposes the proxy, not the internal app.
An additional preparation attempt lost DB command execution immediately after a
live memory-limit reduction; no capacity conclusion is drawn from it. Final
preparation fixes resources from startup and preserves failed fixture state for
diagnosis before explicit ownership-checked cleanup.

### Memory question during the run

A point-in-time host check found 15.94 GiB physical RAM, 5.26 GiB free RAM and
27.30 GiB available commit. A nearby container sample showed app memory about
573 MiB of its 2 GiB limit, versus CPU at approximately its full 2-core budget.
App and proxy `memory.events` max/oom/oom_kill were all zero. MySQL's max counter
was 7,050 while oom/oom_kill remained zero; it was unchanged on two subsequent
reads, including during the final observation.
This is a lifetime counter, including dump preparation, not a measured rate of
reclaim during the load. `max` counts attempts to exceed the memory boundary,
not OOM kills; see the [kernel cgroup documentation](https://docs.kernel.org/admin-guide/cgroup-v2.html).

These observations do not support whole-PC RAM exhaustion as the demonstrated
cause. They do not exclude DB memory/cache pressure or JVM allocation/GC cost:
the app's heap limit is only 240 MiB, independent of its 2 GiB container limit.
One live scrape showed heap usage approximately 156 MiB, one cumulative major-GC
pause (0.154s) and 1,536 evacuation pauses (31.818s total) since process start.
These are not measured-window deltas or a controlled RAM-versus-CPU experiment.
No memory limit, heap, CPU limit or production configuration was changed in
response to the question.

## Source hygiene

Local MySQL credential scripts/exporter configuration, private test Compose,
cookies and Python outputs are ignored. Private notes, generated raw evidence and
pre-existing scratch files use this workstation's `.git/info/exclude`, not public
repository-wide exclusions. No files were deleted for Git cleanup.
