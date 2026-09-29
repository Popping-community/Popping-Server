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
  Extra DB restore resources are removed before HTTP measurement. Primary only,
  one Hikari pool of 50, Redis sessions ON; no Replica freshness claim.
- Target **500 scheduled HTTP requests/s**, no retry, bounded 256 client workers.
  Requests not issued because that bound is reached count as failures. Latency
  includes time from the planned arrival, not just socket time.
- Request shares: guest reads 60%, member reads 30%, guest/member post/comment
  writes 0.5% each, guest reaction add/remove 4% each. Read routes rotate through
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

Capacity results are pending. Preparation failures are preserved, not counted as
load results: local image-ID vs registry-digest build input; occupied fixed port
(an existing process was left untouched); absent published port on the internal
Docker network. The revised fixture exposes the proxy, not the internal app.

## Source hygiene

Local MySQL credential scripts/exporter configuration, private test Compose,
cookies and Python outputs are ignored. Private notes, generated raw evidence and
pre-existing scratch files use this workstation's `.git/info/exclude`, not public
repository-wide exclusions. No files were deleted for Git cleanup.
