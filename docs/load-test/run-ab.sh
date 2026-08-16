#!/bin/bash
# Run one arm of a load-test A/B against a specific app image tag.
#
#   ./run-ab.sh <run-name> <image-tag>
#
# Steps: swap image tag -> recreate apps -> wait healthy -> wait replication lag 0
#        -> warm-up pass(es), discarded, until JIT settles -> wait lag 0 again
#        -> measured pass -> snapshot cache counters -> steady-state verdict.
#
# Why the warm-up passes: recreating the containers gives every run a cold JVM,
# and these apps sit at their CPU quota under load, so C2 competes with request
# threads and warm-up outlasts the 600s test. Measured that way a run reports
# warm-up rather than capacity, and an arm that happened to start warm beats a
# cold one regardless of the code change. Both arms therefore burn identical
# discarded passes first, and the run is gated twice: JIT compilation must have
# flattened before the measured pass starts, and the measured pass itself must
# come out STEADY to be comparable.
#
# Exit codes: 0 usable, 1 setup failure, 2 ran but not comparable.
#
# Known limitations, deliberately not handled here:
#   - The DB is not restored between arms, so writes accumulate across runs
#     (measured: ~10k rows per arm against 1.1M posts / 5.1M comments, about 1%
#     over a full day of runs - negligible in size, but not zero, and there is
#     no step here that undoes it).
#   - Waiting for lag 0 costs more wall time for the faster arm, since it
#     generated more writes. Not waiting is worse: the replica would then spend
#     its CPU on backlog instead of reads, which is the larger distortion.
#   - One arm per invocation. Run them alternating (A B A B A B) and compare
#     medians; a single A/B pair cannot separate the change from run-to-run
#     drift.
set -uo pipefail

if [ $# -ne 2 ]; then
	echo "usage: $0 <run-name> <image-tag>" >&2
	exit 1
fi

RUN_NAME="$1"
IMAGE_TAG="$2"

REPO_DIR="C:/popping-community/popping-server"
JMETER_BIN="C:/apache-jmeter-5.6.3/bin"
COMPOSE="$REPO_DIR/docker-compose.yml"
JMX="$JMETER_BIN/popping-load-test.jmx"
PROM="http://localhost:9090/api/v1/query"
OUT_JTL="$JMETER_BIN/$RUN_NAME.jtl"
OUT_METRICS="$JMETER_BIN/$RUN_NAME-metrics.csv"
OUT_CACHE="$JMETER_BIN/$RUN_NAME-cache.txt"
# Counters are cumulative and the apps are not restarted between the warm-up and
# the measured pass, so a post-run snapshot alone cannot yield the measured
# pass's hit rate. Take a matching pre-run snapshot and difference the two.
OUT_CACHE_PRE="$JMETER_BIN/$RUN_NAME-cache-pre.txt"
OUT_STEADY="$JMETER_BIN/$RUN_NAME-steady.txt"
OUT_META="$JMETER_BIN/$RUN_NAME-meta.txt"

WARMUP_MAX_PASSES="${WARMUP_MAX_PASSES:-3}"
# Set to 1 only to reuse an already-warm JVM: valid just after another pass on
# the same image with no container recreate in between.
WARMUP_SKIP="${WARMUP_SKIP:-0}"
# Warm-up is over once JIT compilation stops growing. Threshold is the share of
# cumulative compile time added during the pass's final 120s.
JIT_SETTLED_RATIO="${JIT_SETTLED_RATIO:-0.02}"
JIT_TAIL_SAMPLES=8
# The warm-up pass ends with a large replication backlog, which drains far
# slower than the initial catch-up (past runs peaked near 450s). Budget more.
LAG_WAIT_TRIES="${LAG_WAIT_TRIES:-100}"
LAG_WAIT_TRIES_POST_WARMUP="${LAG_WAIT_TRIES_POST_WARMUP:-360}"
SETTLE_SECONDS=20

SAMPLER_PID=""

log() { echo "[$(date +%H:%M:%S)] $*"; }

# The sampler runs in the background; without this it survives any early exit
# and keeps polling into the next run's CSV.
cleanup() {
	if [ -n "$SAMPLER_PID" ] && kill -0 "$SAMPLER_PID" 2>/dev/null; then
		log "stopping metric sampler (pid $SAMPLER_PID)"
		kill "$SAMPLER_PID" 2>/dev/null
		wait "$SAMPLER_PID" 2>/dev/null
	fi
}
trap cleanup EXIT INT TERM

abort() {
	log "ABORT: $*"
	exit 1
}

# Latest replication lag in seconds, empty when Prometheus has no sample.
read_lag() {
	curl -s --max-time 10 --get \
		--data-urlencode 'query=mysql_slave_status_seconds_behind_source' "$PROM" \
		| python -c "import sys,json
try:
    r=json.load(sys.stdin)['data']['result']
    print(r[0]['value'][1] if r else '')
except Exception:
    print('')" 2>/dev/null
}

# Block until the replica has caught up, so every pass starts from the same DB
# state. A pass starting hundreds of seconds behind is not comparable to one
# starting at 0.
wait_for_lag_zero() {
	local tries="$1"
	local lag=""
	log "waiting for replication lag to reach 0 (up to $((tries * 5))s) ..."
	for _ in $(seq 1 "$tries"); do
		lag=$(read_lag)
		log "  repl_lag=${lag:-?}"
		[ "$lag" = "0" ] && return 0
		sleep 5
	done
	log "replication lag never reached 0 (last=${lag:-?})"
	return 1
}

# Share of cumulative JIT compile time added during the last JIT_TAIL_SAMPLES
# samples, maximised over the two apps. Empty when the CSV cannot answer it.
jit_growth_ratio() {
	local csv="$1"
	JIT_CSV="$csv" JIT_TAIL="$JIT_TAIL_SAMPLES" python -c "
import csv, os, sys

tail = int(os.environ['JIT_TAIL'])
try:
    with open(os.environ['JIT_CSV'], newline='') as f:
        rows = list(csv.DictReader(f))
except OSError:
    sys.exit(0)

best = None
for col in ('app1_jit_ms', 'app2_jit_ms'):
    vals = []
    for r in rows:
        try:
            vals.append(float(r[col]))
        except (KeyError, TypeError, ValueError):
            continue
    if len(vals) < tail + 1 or vals[-1] <= 0:
        continue
    ratio = (vals[-1] - vals[-1 - tail]) / vals[-1]
    best = ratio if best is None else max(best, ratio)

if best is not None:
    print(f'{best:.4f}')
" 2>/dev/null
}

# Dump every cache counter, not just gets: puts and evictions are what show a
# cache thrashing rather than serving.
snapshot_cache() {
	local out="$1" when="$2" i port
	{
		echo "=== $when cache snapshot ($RUN_NAME) ==="
		for i in 1 2; do
			port=$((8080 + i))
			curl -s --max-time 10 "http://127.0.0.1:$port/actuator/prometheus" \
				| grep '^cache_' | sed "s|^|app$i |"
		done
	} > "$out"
	log "cache snapshot ($when) -> $out"
}

# One JMeter pass plus its metric sampler. The .jmx writes results to
# popping_results.jtl itself, so rotate that file before every pass.
run_pass() {
	local label="$1" jtl="$2" metrics="$3" status

	log "starting metric sampler -> $metrics"
	bash "$REPO_DIR/docs/load-test/sample-metrics.sh" "$metrics" \
		> "$JMETER_BIN/$label-sampler.log" 2>&1 &
	SAMPLER_PID=$!

	log "starting JMeter pass '$label' (600s) ..."
	cd "$JMETER_BIN" || return 1
	rm -f "$JMETER_BIN/popping_results.jtl"
	./jmeter.bat -n -t "$JMX" \
		-j "$JMETER_BIN/$label-jmeter.log" > "$JMETER_BIN/$label-console.log" 2>&1
	status=$?
	log "JMeter pass '$label' finished (exit=$status)"

	wait "$SAMPLER_PID" 2>/dev/null
	SAMPLER_PID=""

	[ "$status" -eq 0 ] || return "$status"
	[ -s "$JMETER_BIN/popping_results.jtl" ] || { log "no results written"; return 1; }
	cp "$JMETER_BIN/popping_results.jtl" "$jtl" || return 1
	log "results -> $jtl"
}

# Everything needed to tell later whether two runs were actually the same
# experiment. The executing .jmx lives outside the repo, so its hash matters.
write_metadata() {
	{
		echo "run_name=$RUN_NAME"
		echo "image_tag=$IMAGE_TAG"
		echo "started=$(date +%Y-%m-%dT%H:%M:%S)"
		echo "git_head=$(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null)"
		echo "git_dirty=$(git -C "$REPO_DIR" status --porcelain 2>/dev/null | wc -l) files"
		echo "jmx=$JMX"
		echo "jmx_sha256=$(sha256sum "$JMX" 2>/dev/null | cut -d' ' -f1)"
		echo "jmx_repo_sha256=$(sha256sum "$REPO_DIR/docs/load-test/popping-load-test.jmx" 2>/dev/null | cut -d' ' -f1)"
		echo "jmeter_setenv=$(grep -i '^set ' "$JMETER_BIN/setenv.bat" 2>/dev/null | tr '\n' ';')"
		echo "app1_image_id=$(docker inspect -f '{{.Image}}' popping-app-1 2>/dev/null)"
		echo "app2_image_id=$(docker inspect -f '{{.Image}}' popping-app-2 2>/dev/null)"
	} > "$OUT_META"
	log "metadata -> $OUT_META"
}

log "=== RUN $RUN_NAME  image=$IMAGE_TAG ==="

[ -f "$JMX" ] || abort "test plan not found: $JMX"

# 1. Point both app services at the requested image. Both app services must
#    match, or the two containers would run different code.
sed -i -E "s|(image: chooh1010/popping-community:).*|\1${IMAGE_TAG}|" "$COMPOSE" \
	|| abort "failed to rewrite image tag in $COMPOSE"
tagged=$(grep -c "image: chooh1010/popping-community:${IMAGE_TAG}\$" "$COMPOSE")
[ "$tagged" -eq 2 ] || abort "expected 2 app services on tag $IMAGE_TAG, found $tagged"
grep -n "chooh1010/popping-community" "$COMPOSE"

# 2. Recreate app containers only; DB and monitoring stay up.
cd "$REPO_DIR" || abort "cannot enter $REPO_DIR"
docker compose config -q || abort "docker-compose.yml does not render"
docker compose up -d --force-recreate app-1 app-2 || abort "failed to recreate app containers"

# 3. Wait until both apps report UP.
for port in 8081 8082; do
	log "waiting for app on $port ..."
	up=0
	for _ in $(seq 1 90); do
		if curl -s --max-time 3 "http://127.0.0.1:$port/actuator/health" | grep -q '"status":"UP"'; then
			log "app on $port is UP"
			up=1
			break
		fi
		sleep 2
	done
	[ "$up" -eq 1 ] || abort "app on $port never came up"
done

write_metadata
if [ "$(grep '^jmx_sha256=' "$OUT_META" | cut -d= -f2)" \
	!= "$(grep '^jmx_repo_sha256=' "$OUT_META" | cut -d= -f2)" ]; then
	log "NOTE: executing .jmx differs from the repo copy - see $OUT_META"
fi

# 4. Both passes start from a caught-up replica.
wait_for_lag_zero "$LAG_WAIT_TRIES" || abort "replica not caught up before warm-up"

# 5. Discarded warm-up pass(es), until C2 has finished compiling the hot paths.
if [ "$WARMUP_SKIP" = "1" ]; then
	log "WARMUP_SKIP=1 - assuming the JVM is already warm"
else
	pass=0
	while [ "$pass" -lt "$WARMUP_MAX_PASSES" ]; do
		pass=$((pass + 1))
		sleep "$SETTLE_SECONDS"
		label="$RUN_NAME-warmup$pass"
		run_pass "$label" "$JMETER_BIN/$label.jtl" "$JMETER_BIN/$label-metrics.csv" \
			|| abort "warm-up pass $pass failed"
		wait_for_lag_zero "$LAG_WAIT_TRIES_POST_WARMUP" \
			|| abort "replica not caught up after warm-up pass $pass"

		ratio=$(jit_growth_ratio "$JMETER_BIN/$label-metrics.csv")
		if [ -z "$ratio" ]; then
			log "warm-up pass $pass: JIT growth unavailable - proceeding on one pass"
			break
		fi
		log "warm-up pass $pass: JIT growth in final $((JIT_TAIL_SAMPLES * 15))s = $ratio (settled <= $JIT_SETTLED_RATIO)"
		if awk "BEGIN{exit !($ratio <= $JIT_SETTLED_RATIO)}"; then
			log "JIT settled after $pass warm-up pass(es)"
			break
		fi
		[ "$pass" -lt "$WARMUP_MAX_PASSES" ] || log "WARNING: JIT still growing after $pass passes - measuring anyway"
	done
fi

# 6. Measured pass.
sleep "$SETTLE_SECONDS"
snapshot_cache "$OUT_CACHE_PRE" "pre-run"
run_pass "$RUN_NAME" "$OUT_JTL" "$OUT_METRICS" || abort "measured pass failed"

# 7. Snapshot cache counters before anything else touches the apps.
snapshot_cache "$OUT_CACHE" "post-run"

# 8. Gate: a run that was still warming measured warm-up, not capacity.
log "steady-state verdict:"
python "$REPO_DIR/docs/load-test/check-steady-state.py" "$OUT_JTL" | tee "$OUT_STEADY"
if grep -qE '^[[:space:]]+STEADY' "$OUT_STEADY"; then
	log "=== RUN $RUN_NAME DONE (STEADY - usable) ==="
	exit 0
fi
log "=== RUN $RUN_NAME DONE (NOT STEADY - do not compare this run) ==="
exit 2
