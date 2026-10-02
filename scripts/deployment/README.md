# Local candidate admission check

For the 2026-09-29 regression/commit closeout and CI digest handoff, see
[the follow-up report](../../docs/load-test/deployment-finish-20260929.md).
`deploy_digest.sh` is separate from these local controllers: it checks image
identity, but does not implement readiness-gated rolling deployment.

For the combined admission → retention → expiry/recovery procedure, read
[the local runbook](../../docs/load-test/deployment-runbook-20260923.md) and
[the current configuration audit](../../docs/load-test/deployment-integration-audit-20260923.md).
They distinguish repository configuration, observed local containers and the
uninspected remote host; these scripts are not yet a production rollout system.

This tool validates one small deployment step: keep a candidate excluded from
HAProxy until its application-port readiness and an authenticated business GET
pass. It does not stop, drain, replace, or roll back the old server.

The application opts in to `application-deployment.properties` through the
`deployment` Spring profile or an explicit config import. The resource enables
`/readyz` and `/livez` on the business port and declares graceful shutdown. A
deployment must also provide a container shutdown grace period; the profile alone
does not ensure that requests finish during termination.

## Preconditions

- HAProxy has a named old server already UP and a distinct candidate in MAINT.
- The candidate's HAProxy HTTP health check expects `/readyz` status 200.
- Direct candidate HTTP and HAProxy control endpoints are published only on
  `127.0.0.1`. The TCP admin interface is for an isolated local fixture; it is not
  a recommended public or production administration endpoint.
- A Netscape-format cookie file contains a session created by logging in to the
  old app. The candidate shares the session store. Do not commit this cookie file.
- `/boards/new` is a protected GET. A caller without that session must be denied;
  the selected expected marker must distinguish the protected page from a login
  page. The tool never follows redirects or environment HTTP proxies.
- A single controller owns the candidate for the duration of the command.
  Concurrent deployments and DNS changes are outside this tool's contract.

```powershell
python scripts/deployment/admit_candidate.py `
  --admin-port 19399 --target apps/candidate --old apps/old `
  --candidate-url http://127.0.0.1:19392 `
  --cookie-file <private-local-cookie-file> `
  --marker '<title>게시판 생성 - 커뮤니티</title>' `
  --timeout 10 --output <new-result-file.json>
```

Three consecutive readiness 200/UP and protected-page 200/marker checks permit
the `ready` command. Success additionally requires HAProxy UP/L7OK. This confirms
the readiness HTTP check, not JVM warmup or every dependency's health. Actual
candidate traffic must be checked separately; the integration fixture does so.

If the direct checks time out, the tool never changes server state. If proxy
confirmation fails after admission, the tool requests MAINT and re-reads it.
`admitted: null` with `admission_unknown_maint_unconfirmed` means that recovery
could not be verified. This is not a successful rejection; inspect the proxy
before further deployment. Only `admitted: true` exits with code 0.

`--timeout` bounds each polling phase between checks. Individual network calls
have two-second timeouts, so wall time can exceed the phase deadline by the calls
already in progress. It is not a hard end-to-end deployment SLA.

## Verification

```powershell
python -m unittest discover -s scripts/deployment -p 'test_*.py' -v
```

The real Spring/MySQL/Redis/HAProxy experiment, precise failure injection, and
limits are documented in `docs/load-test/deployment-admission-20260922.md`.
The existing CI SSH deployment does not call this tool. No remote rollout has
been performed as part of this change.

## Local request quiescence and termination

`quiesce_server.py` is a separate experimental step after candidate admission.
It requires an UP old server and a distinct survivor UP with a last successful
HTTP check. HAProxy's `* L7OK` means a new check is in progress with the previous
successful result; it is accepted alongside `L7OK`. This is an observation, not
a guarantee that the survivor cannot fail immediately afterwards.

The controller puts old in MAINT, checks that its cumulative session count no
longer grows, and waits for three consecutive zero current-session/queue samples.
It rechecks both servers and the polling deadline, verifies the exact old
container ID, Compose labels, start time and proxy address, then sends SIGTERM.
Only disposable projects named `popping-drain-check-*` are accepted. It sends no
SIGKILL and has no automatic ready/retry fallback. One controller and unchanged
proxy configuration are required throughout the operation.

```powershell
python scripts/deployment/quiesce_server.py `
  --admin-port 19499 --target apps/old --survivor apps/candidate `
  --container-id <exact-64-character-ID> `
  --project popping-drain-check-20260922 --service old `
  --timeout 15 --shutdown-timeout 35 --output <new-result-file.json>
```

Busy timeout leaves the server running in MAINT; inspect it before deciding how
to recover. A control error aborts further actions. If a stop callback has begun
but its outcome cannot be established, `stopped: null` represents uncertainty.
Exit 0 means container exit was observed, not proof that its application ended
successfully: inspect the exit code and OOM flag too. Polling timeouts are not
hard wall-clock bounds; in-progress network/Docker calls have their own bounds.

HAProxy session counts do not cover detached tasks, WebSockets, consumers,
requests bypassing the proxy, or JDBC work surviving an HTTP timeout. This tool
is not a general application in-flight counter. The integration experiment adds
a real MySQL lock barrier, POST response and DB checks to validate its observed
requests. It does not test SIGTERM arriving during a still-active request.
See `docs/load-test/deployment-drain-20260922.md` for results and limitations.

## Cold recovery of a retained previous image

`recover_server.py` handles a different precondition from admission: the failed
container has exited and its HAProxy slot is deliberately fenced in MAINT. There
is no healthy survivor requirement. The recovery slot must also start in MAINT.
This local-only tool does not recreate containers or detect crashes itself.

Callers recreate the retained exact previous image, inspect its new address, and
update the excluded proxy slot before invoking the gate. Required identity inputs
are `--container-id`, `--service`, `--image-id`, `--failed-container-id`,
`--failed-service`, `--failed-image-id` and `--project`, plus `--target`, `--failed`,
`--admin-port`, `--candidate-url`, `--cookie-file`, `--marker`, `--output` and an
optional `--timeout`. Image values must be exact sha256 IDs. Logical server names
must match Compose service labels; projects use the disposable drain namespace.

The gate verifies the recovery image, running state, start time, proxy address and
loopback business-port binding. It also checks that the exact failed image is
still the same exited container. Three readiness and authenticated marker checks
permit ready only after these conditions are rechecked. Success requires proxy
UP with a last successful HTTP check, identity revalidation, and failed-slot MAINT.

Checks failing before ready leave the recovery slot excluded. Errors after a
ready attempt trigger MAINT and confirmation; `recovered: null` means this
exclusion could not be confirmed. The caller must independently check the actual
version/body through the proxy after `recovered: true`. The tool does not claim
zero downtime, monitor recovery afterwards, or validate migration compatibility.
Polling deadlines allow bounded individual calls already in progress to finish.

## Guarded fixture entry

`verified_fixture.py` is a small caller helper. `CleanupGuard` records observed
container IDs after each creation attempt, checks ownership before cleanup, and
refuses unknown IDs. `run_preserving_failure` keeps the original failure when
cleanup also fails. `admit_verified` reuses `RetainedPair.verify` before the
caller invokes admission; the caller must bind its actual CLI arguments to that
same pair/runtime. A unique project namespace and a single caller are required;
these checks are not an atomic lock or source/build provenance validation.

Cleanup is skipped only when creation was never attempted. If the Compose file
is unavailable after a creation attempt, cleanup raises before any Docker
callback rather than reporting `no_owned_creation_attempt`. The owner history
is retained. `run_preserving_failure` records `cleanup_error` separately from
the original `error`; cleanup failure also fails an otherwise successful run.
After restoring the original Compose file, a caller may retry through the same
ownership checks. This does not automatically recover leaked resources or
validate arbitrary replacement file contents. See the
[missing-Compose verification](../../docs/load-test/cleanup-missing-compose-20260923.md).

The new adapter and executed-source snapshot are documented in the
[review decision](../../_workspace/deploy-safety/2026-09-23/claude-review-v1/decision.md).
Existing experiment and controller sources remain unchanged.

## Bounded retention before local expiry

`retain_previous.py` is an importable synchronous procedure, not a daemon. The
caller must first admit the new release, verify its actual proxy response, and
park the previous server in MAINT. Pin both `OwnedContainer` objects and exact
image IDs before calling `RetainedPair` and `retain`. The protected smoke callback
must GET `pair.new_url` with the existing SESSION and expected new-release marker.

The procedure preserves MAINT, unchanged cumulative sessions, both exact running
processes/images/addresses, and survivor health through a caller-selected hold
period. After expiry it requires three zero session/queue samples, authenticated
business smoke and final identity/state checks before the previous process gets
SIGTERM. It never sends SIGKILL or admits a server. `hold_seconds` is a minimum
retention period, not an exact stop time. `drain_timeout` bounds polling after
expiry; individual network/Docker calls and the owned shutdown timeout also take
time. No production duration is inferred from the three-second fixture.

Use one controller for the entire project. If retention aborts, the caller may
separately use `recover_server.py` only after confirming the failed exact new
container has exited and fencing its slot in MAINT. Health failure alone does
not meet that recovery precondition. There is no independent expiry timer to
race that recovery. Process restart/resume and multi-controller locking are not
implemented; after interruption inspect the saved/actual state manually.

Interpret the fields together: `stopped=False, stop_invoked=False` means no stop
callback was invoked; `False, True` means SIGTERM did not yield a confirmed exit
within its observation bound; `None, True` means the stop outcome is unknown.
`True` confirms exit, not successful application shutdown: check exit code/OOM.

The final survivor check and SIGTERM cannot be atomic. A failure after that check
may still cause an outage. This procedure only rejects failures visible before
the final check; it is not a production HA guarantee. Session/queue counts do
not cover detached work, schedulers or bypass traffic. See the isolated report
`docs/load-test/deployment-retention-20260923.md` for scenarios and limitations.
