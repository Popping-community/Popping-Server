#!/bin/bash
# Sample host/container state during a load test.
#
#   ./sample-metrics.sh <out.csv> [samples] [interval_seconds]
#
# Beyond CPU and replication lag, this records the signals needed to decide
# objectively whether the system has finished warming up:
#   - app*_jit_ms : cumulative JIT compilation time. Warm-up is over once this
#                   stops growing; a still-climbing value means C2 is competing
#                   with request threads for the container's CPU quota.
#   - app*_gc_s   : cumulative GC pause time.
#   - app*_busy   : busy Tomcat threads (server-side saturation).
#   - jmeter_cpu_s: cumulative CPU seconds consumed by JMeter on the host.
#                   JMeter runs as java.exe on Windows while the app JVMs live
#                   inside containers, so this isolates client-side saturation
#                   from server-side. Differentiate consecutive samples to get
#                   cores used; approaching the host core count means the load
#                   generator itself is the bottleneck.
#
# All Prometheus queries for one sample run in a single python process; the
# sampler shares the host with JMeter, so per-sample process spawns are kept low.
# SAMPLES is an upper bound, not a duration: the loop exits on its own once
# JMeter finishes, so the default just has to outlast warm-up plus measurement.
OUT="${1:-}"
SAMPLES="${2:-160}"
INTERVAL="${3:-15}"

# A single zero reading is ambiguous - JMeter may have exited, or the CIM query
# may have just failed transiently. Require consecutive zeros before believing
# the test is over, so one flaky sample cannot kill metric collection mid-run.
ZERO_CONFIRM_SAMPLES="${ZERO_CONFIRM_SAMPLES:-2}"
# If JMeter never appears at all (failed launch), give up instead of running the
# full SAMPLES budget while run-ab.sh sits in 'wait'.
STARTUP_GRACE_SAMPLES="${STARTUP_GRACE_SAMPLES:-20}"
export PROM_URL="${PROM_URL:-http://localhost:9090/api/v1/query}"

if [ -z "$OUT" ]; then
	echo "usage: $0 <out.csv> [samples] [interval_seconds]" >&2
	exit 1
fi

HEADER="ts,iso,app1_cpu,app2_cpu,master_cpu,replica_cpu,repl_lag_s,jmeter_cpu_s,app1_jit_ms,app2_jit_ms,app1_gc_s,app2_gc_s,app1_busy,app2_busy"

# One instant query per metric; empty field on any failure so a transient
# scrape gap never aborts the run.
sample_prom() {
	python - <<'PY'
import json
import os
import urllib.parse
import urllib.request

PROM = os.environ["PROM_URL"]
QUERIES = [
    'dockerstats_cpu_usage_ratio{name="popping-app-1"}',
    'dockerstats_cpu_usage_ratio{name="popping-app-2"}',
    'dockerstats_cpu_usage_ratio{name="popping-mysql-master"}',
    'dockerstats_cpu_usage_ratio{name="popping-mysql-replica"}',
    'mysql_slave_status_seconds_behind_source',
    'jvm_compilation_time_ms_total{instance="app-1"}',
    'jvm_compilation_time_ms_total{instance="app-2"}',
    'sum(jvm_gc_pause_seconds_sum{instance="app-1"})',
    'sum(jvm_gc_pause_seconds_sum{instance="app-2"})',
    'tomcat_threads_busy_threads{instance="app-1"}',
    'tomcat_threads_busy_threads{instance="app-2"}',
]


def query(expr: str) -> str:
    url = f"{PROM}?{urllib.parse.urlencode({'query': expr})}"
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            result = json.load(resp)["data"]["result"]
        return result[0]["value"][1] if result else ""
    except Exception:
        return ""


print(",".join(query(expr) for expr in QUERIES))
PY
}

# Cumulative CPU seconds of the JMeter JVM on the Windows host. Matches on the
# command line rather than the process name: IntelliJ and the Gradle daemon are
# java.exe too, and summing every JVM would drown out the client-side signal.
# Formats with InvariantCulture so a locale-specific thousands separator can
# never inject a comma into the CSV. Prints 0 when JMeter is not running.
jmeter_cpu_seconds() {
	powershell -NoProfile -Command "\$ids = @(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*ApacheJMeter*' } | Select-Object -ExpandProperty ProcessId); \$total = 0.0; if (\$ids) { \$total = (Get-Process -Id \$ids -ErrorAction SilentlyContinue | Measure-Object -Property CPU -Sum).Sum }; if (\$null -eq \$total) { \$total = 0.0 }; [math]::Round(\$total, 1).ToString([System.Globalization.CultureInfo]::InvariantCulture)" 2>/dev/null | tr -d '\r'
}

echo "$HEADER" > "$OUT"

seen_jmeter=0
zero_streak=0
sample_no=0

for _ in $(seq 1 "$SAMPLES"); do
	sample_no=$((sample_no + 1))
	ts=$(date +%s)
	iso=$(date +%H:%M:%S)
	prom=$(sample_prom)
	jm=$(jmeter_cpu_seconds)

	if awk "BEGIN{exit !(\"${jm:-x}\" + 0 > 0)}" 2>/dev/null; then
		seen_jmeter=1
		zero_streak=0
	else
		zero_streak=$((zero_streak + 1))
		if [ "$seen_jmeter" = "1" ]; then
			# JMeter was running and now reads zero. Record the field as missing
			# rather than 0: a fake zero would show up as a negative delta if this
			# turns out to be a transient query failure.
			jm=""
			if [ "$zero_streak" -ge "$ZERO_CONFIRM_SAMPLES" ]; then
				echo "JMeter exited - stopping sampler"
				break
			fi
		else
			# Not started yet; zero is the honest value.
			jm=0
			if [ "$sample_no" -ge "$STARTUP_GRACE_SAMPLES" ]; then
				echo "JMeter never started within $STARTUP_GRACE_SAMPLES samples - stopping sampler" >&2
				break
			fi
		fi
	fi

	# Column order follows HEADER: the 5 infra metrics, then jmeter_cpu_s, then the JVM ones.
	infra=$(echo "$prom" | cut -d, -f1-5)
	jvm=$(echo "$prom" | cut -d, -f6-11)
	echo "$ts,$iso,$infra,$jm,$jvm" >> "$OUT"
	sleep "$INTERVAL"
done
echo "SAMPLER_DONE"
