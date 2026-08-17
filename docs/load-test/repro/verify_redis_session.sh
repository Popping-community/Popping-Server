#!/bin/bash
# One load pass to answer the questions a Redis session store actually raises.
#
# Not an A/B: the per-request cost is one HGETALL at 0.004ms against ~31%
# authenticated traffic, which works out around 0.02% of a 350ms response - two
# orders of magnitude under this load model's ~4% detection limit. Six arms would
# spend five hours confirming arithmetic. What is worth measuring is capacity and
# behaviour: is Redis anywhere near its limit, does session memory fit, does the
# load actually spread once the sticky cookie is gone, and does anything error.
set -u

OUT=C:/apache-jmeter-5.6.3/bin
NAME=redis-session-verify-20260817
CSV="$OUT/$NAME-redis.csv"
JMETER_BIN=/c/apache-jmeter-5.6.3/bin

r() { docker exec popping-redis redis-cli "$@" 2>/dev/null | tr -d '\r'; }
hap() { curl -s --max-time 5 "http://127.0.0.1:9404/stats;csv" 2>/dev/null; }

echo "iso,redis_cmds,redis_mem_bytes,sessions,app1_stot,app2_stot" > "$CSV"

sample() {
  local cmds mem sess a1 a2 stats
  cmds=$(r INFO commandstats | awk -F'[:,=]' '/^cmdstat_/{s+=$3} END{print s+0}')
  mem=$(r INFO memory | awk -F: '/^used_memory:/{print $2+0}')
  sess=$(r DBSIZE)
  stats=$(hap)
  a1=$(echo "$stats" | awk -F, '$1=="app_servers" && $2=="app1"{print $8+0}')
  a2=$(echo "$stats" | awk -F, '$1=="app_servers" && $2=="app2"{print $8+0}')
  echo "$(date +%Y-%m-%dT%H:%M:%S),${cmds:-0},${mem:-0},${sess:-0},${a1:-0},${a2:-0}" >> "$CSV"
}

echo "[$(date +%H:%M:%S)] resetting Redis counters"
r CONFIG RESETSTAT >/dev/null
echo "  sessions before: $(r DBSIZE)"

echo "[$(date +%H:%M:%S)] starting sampler -> $CSV"
( while true; do sample; sleep 15; done ) &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null' EXIT INT TERM

echo "[$(date +%H:%M:%S)] starting JMeter pass (600s)"
cd "$JMETER_BIN" || exit 1
rm -f popping_results.jtl
./jmeter.bat -n -t "$JMETER_BIN/popping-load-test.jmx" \
  -j "$JMETER_BIN/$NAME-jmeter.log" > "$JMETER_BIN/$NAME-console.log" 2>&1
echo "[$(date +%H:%M:%S)] JMeter finished (exit=$?)"
cp popping_results.jtl "$JMETER_BIN/$NAME.jtl"

sample
kill $SAMPLER 2>/dev/null

echo "[$(date +%H:%M:%S)] sessions after: $(r DBSIZE)"
echo "done -> $CSV and $NAME.jtl"
