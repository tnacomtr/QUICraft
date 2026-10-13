#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Load spike: one real QuicServer (load-server) and one load client (load-client) on this box,
# transport only, no Minecraft protocol. Samples RSS and MemAvailable every 5 s and aborts both
# JVMs (exit 3) on a memory guardrail. Results: testkit/results/load-spike-<UTC stamp>/.
#
#   ./gradlew :testkit:installDist
#   COUNT=1000 RAMP_RATE=200 HOLD_SEC=20 bash testkit/run-load-spike.sh     # smoke
#   bash testkit/run-load-spike.sh                                         # 50k, 500/s, 120 s hold
#
# Env: COUNT, RAMP_RATE, HOLD_SEC, LOAD_BIND, SOURCE_BASE, IDLE_MIN, MAX_RSS_MB, MAX_FAIL_PCT,
# SESSION_RESUMPTION (1 to allow), LAUNCHER (installed testkit launcher), NO_SCOPE (1 skips
# systemd-run even when present). Each JVM runs under systemd-run --user --scope with
# MemoryMax=16G when systemd-run exists; otherwise plain java.
#
# RSS sampling and cleanup use the JVM's own printed PID line: under systemd-run --scope $! is
# systemd-run, not the JVM, and the launcher execs java only in the plain case.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

COUNT="${COUNT:-50000}"
RAMP_RATE="${RAMP_RATE:-500}"
HOLD_SEC="${HOLD_SEC:-120}"
LOAD_BIND="${LOAD_BIND:-127.0.0.1:65530}"
SOURCE_BASE="${SOURCE_BASE:-127.1.0.0}"
IDLE_MIN="${IDLE_MIN:-10}"
MAX_RSS_MB="${MAX_RSS_MB:-12288}"
MAX_FAIL_PCT="${MAX_FAIL_PCT:-5}"
LAUNCHER="${LAUNCHER:-$root/testkit/build/install/quicraft-testkit/bin/quicraft-testkit}"
JVM_OPTS="-Xmx8g -XX:MaxDirectMemorySize=8g"
MIN_AVAIL_KB=$((8 * 1024 * 1024))
SAMPLE_SEC=5

[[ -x "$LAUNCHER" ]] || { echo "missing $LAUNCHER: run ./gradlew :testkit:installDist" >&2; exit 2; }

stamp="$(date -u +%Y%m%d-%H%M%S)"
results="$here/results/load-spike-$stamp"
mkdir -p "$results"
samples="$results/samples.tsv"
printf 'timestamp\tclient_rss_mb\tserver_rss_mb\tmem_available_mb\n' > "$samples"

server_pid=""
client_pid=""
server_jvm_pid=""
client_jvm_pid=""
LAST_PID=""

# launch <log> <subcommand> [args...]: starts one JVM in the background, sets LAST_PID.
launch() {
    local log="$1"
    shift
    if [[ -z "${NO_SCOPE:-}" ]] && command -v systemd-run >/dev/null 2>&1; then
        JAVA_OPTS="$JVM_OPTS" systemd-run --user --scope --collect -p MemoryMax=16G -p MemorySwapMax=0 \
            -- nice -n 5 "$LAUNCHER" "$@" > "$log" 2>&1 &
    else
        JAVA_OPTS="$JVM_OPTS" nice -n 5 "$LAUNCHER" "$@" > "$log" 2>&1 &
    fi
    LAST_PID=$!
}

# jvm_pid <log>: the JVM's own PID line, empty after 30 s.
jvm_pid() {
    local log="$1" pid=""
    for _ in $(seq 1 30); do
        pid="$(awk '/^PID / {print $2; exit}' "$log" 2>/dev/null || true)"
        [[ -n "$pid" ]] && break
        sleep 1
    done
    printf '%s' "$pid"
}

# rss_mb <pid>: VmRSS in MiB, 0 once the process is gone.
rss_mb() {
    awk '/^VmRSS:/ {print int($2 / 1024)}' "/proc/$1/status" 2>/dev/null || echo 0
}

mem_available_kb() {
    awk '/^MemAvailable:/ {print $2}' /proc/meminfo
}

# Kills whatever is still running, on any exit (trap EXIT). Keeps the script's exit status.
cleanup() {
    local pid
    for pid in "$client_jvm_pid" "$server_jvm_pid"; do
        [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && kill -TERM "$pid" 2>/dev/null || true
    done
    for pid in "$client_jvm_pid" "$server_jvm_pid"; do
        [[ -n "$pid" ]] || continue
        for _ in $(seq 1 10); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$pid" 2>/dev/null; then
            kill -KILL "$pid" 2>/dev/null || true
        fi
    done
    for pid in "$client_pid" "$server_pid"; do
        [[ -n "$pid" ]] && kill -TERM "$pid" 2>/dev/null || true
    done
    for pid in "$client_pid" "$server_pid"; do
        [[ -n "$pid" ]] || continue
        wait "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT

fail() {
    echo "WATCHDOG: $*" >&2
    exit 3
}

sample() {
    local ts crss srss avail_kb
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    crss="$(rss_mb "$client_jvm_pid")"
    srss="$(rss_mb "$server_jvm_pid")"
    avail_kb="$(mem_available_kb)"
    printf '%s\t%s\t%s\t%s\n' "$ts" "$crss" "$srss" "$((avail_kb / 1024))" >> "$samples"
    if (( avail_kb < MIN_AVAIL_KB )); then
        fail "MemAvailable $((avail_kb / 1024)) MiB below 8 GiB"
    fi
    if (( crss > MAX_RSS_MB )); then
        fail "client RSS $crss MiB above cap $MAX_RSS_MB MiB"
    fi
    kill -0 "$server_jvm_pid" 2>/dev/null || fail "server exited during the run"
}

launch "$results/server.log" load-server --bind "$LOAD_BIND" --status-interval 5 --idle-minutes "$IDLE_MIN"
server_pid="$LAST_PID"
server_jvm_pid="$(jvm_pid "$results/server.log")"
[[ -n "$server_jvm_pid" ]] || { echo "server printed no PID line; see $results/server.log" >&2; exit 1; }

for _ in $(seq 1 30); do
    if grep -q '^FINGERPRINT ' "$results/server.log" 2>/dev/null && grep -q '^BOUND ' "$results/server.log" 2>/dev/null; then
        break
    fi
    kill -0 "$server_jvm_pid" 2>/dev/null || { echo "server exited before binding; see $results/server.log" >&2; exit 1; }
    sleep 1
done
grep -q '^FINGERPRINT ' "$results/server.log" && grep -q '^BOUND ' "$results/server.log" \
    || { echo "server did not print BOUND and FINGERPRINT within 30 s" >&2; exit 1; }
fingerprint="$(awk '/^FINGERPRINT / {print $2}' "$results/server.log")"
remote="$(awk '/^BOUND / {print $2 ":" $3}' "$results/server.log")"
echo "server bound at $remote, $fingerprint"

client_args=(load-client --remote "$remote" --fingerprint "$fingerprint" --count "$COUNT"
    --rate "$RAMP_RATE" --source-base "$SOURCE_BASE" --hold-sec "$HOLD_SEC" --idle-minutes "$IDLE_MIN"
    --status-interval 5 --max-rss-mb "$MAX_RSS_MB" --max-fail-pct "$MAX_FAIL_PCT")
[[ -n "${SESSION_RESUMPTION:-}" ]] && client_args+=(--session-resumption)
launch "$results/client.log" "${client_args[@]}"
client_pid="$LAST_PID"
client_jvm_pid="$(jvm_pid "$results/client.log")"
[[ -n "$client_jvm_pid" ]] || { echo "client printed no PID line; see $results/client.log" >&2; exit 1; }

while kill -0 "$client_jvm_pid" 2>/dev/null; do
    sample
    sleep "$SAMPLE_SEC"
done
sample || true

client_rc=0
wait "$client_pid" || client_rc=$?
client_pid=""
client_jvm_pid=""

kill -TERM "$server_jvm_pid" 2>/dev/null || true
server_rc=0
wait "$server_pid" || server_rc=$?
server_pid=""
server_jvm_pid=""

echo "--- load spike results: $results"
echo "last sample: $(tail -n 1 "$samples")"
echo "client: $(grep '^SUMMARY ' "$results/client.log" || echo 'no SUMMARY line; see client.log')"
echo "server: $(grep '^CLOSED ' "$results/server.log" || echo 'no CLOSED line')"
echo "exit codes: client=$client_rc server=$server_rc"
exit "$client_rc"
