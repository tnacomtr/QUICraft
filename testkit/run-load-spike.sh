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
#   MODE=v3 COUNT=1000 RAMP_RATE=200 HOLD_SEC=20 bash testkit/run-load-spike.sh   # /3 connections
#   MODE=v3 LANES=1 NODE_BUDGET_MB=1 COUNT=100 RAMP_RATE=50 MAX_FAIL_PCT=100 bash testkit/run-load-spike.sh
#
# Env: COUNT, RAMP_RATE, HOLD_SEC, LOAD_BIND, SOURCE_BASE, IDLE_MIN, MAX_RSS_MB, MAX_FAIL_PCT,
# SESSION_RESUMPTION (1 to allow), SHIPPED_LIMITS (1 measures the shipped admission defaults
# instead of the raised ones), SOCKETS (SO_REUSEPORT socket count, default 1), LAUNCHER (installed testkit launcher), NO_SCOPE (1 skips
# systemd-run even when present), MEM_MAX (per-JVM systemd MemoryMax, default 16G), JVM_OPTS.
# MODE: v2 (default, the /2 listener and source-address dials, one loopback IP per client) or v3
# (a /3 listener with multi-stream and a node pending-bytes budget of NODE_BUDGET_MB, default 256,
# and connectV3 dials from one loopback IP). LANES=1 (v3 only) also activates each connection's
# three lanes with an idle game, without a Minecraft binding proof (transport-level spike only).
# The client counts a /3 connection the node budget refuses as connected then closed, so the
# SUMMARY line's live= is the held count; server admitted= is the /3 acceptor count.
# Each JVM runs under systemd-run --user --scope with MemoryMax when systemd-run exists; otherwise
# plain java.
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
JVM_OPTS="${JVM_OPTS:--Xmx8g -XX:MaxDirectMemorySize=8g}"
MODE="${MODE:-v2}"
LANES="${LANES:-}"
NODE_BUDGET_MB="${NODE_BUDGET_MB:-256}"
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
        JAVA_OPTS="$JVM_OPTS" systemd-run --user --scope --collect -p MemoryMax="${MEM_MAX:-16G}" -p MemorySwapMax=0 \
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
    if (( srss > MAX_RSS_MB )); then
        fail "server RSS $srss MiB above cap $MAX_RSS_MB MiB"
    fi
    kill -0 "$server_jvm_pid" 2>/dev/null || fail "server exited during the run"
}

server_args=(load-server --bind "$LOAD_BIND" --status-interval 5 --idle-minutes "$IDLE_MIN")
[[ "${SHIPPED_LIMITS:-0}" == "1" ]] && server_args+=(--shipped-limits)
[[ -n "${SOCKETS:-}" ]] && server_args+=(--sockets "$SOCKETS")
case "$LANES" in
    ""|0) LANES="" ;;
    1) ;;
    *) echo "LANES must be empty, 0 or 1, got $LANES" >&2; exit 2 ;;
esac
if [[ "${SHIPPED_LIMITS:-0}" == "1" && "$MODE" == v3 ]]; then
    echo "WARNING: SHIPPED_LIMITS=1 with MODE=v3: the per-IP open cap (64) limits a single-loopback-IP v3 run" >&2
fi
case "$MODE" in
    v2) [[ -z "$LANES" ]] || { echo "LANES needs MODE=v3" >&2; exit 2; } ;;
    v3) server_args+=(--mode v3 --node-budget-mb "$NODE_BUDGET_MB")
        [[ -n "$LANES" ]] && server_args+=(--lanes) ;;
    *) echo "MODE must be v2 or v3, got $MODE" >&2; exit 2 ;;
esac
launch "$results/server.log" "${server_args[@]}"
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
if command -v ss >/dev/null 2>&1; then
    # The sockets actually bound: core falls back to one where SO_REUSEPORT is unavailable.
    bound_sockets="$(ss -Huan "sport = :${remote##*:}" | wc -l)"
    echo "UDP sockets bound on port ${remote##*:}: $bound_sockets" | tee "$results/sockets.txt"
fi

client_args=(load-client --remote "$remote" --fingerprint "$fingerprint" --count "$COUNT"
    --rate "$RAMP_RATE" --source-base "$SOURCE_BASE" --hold-sec "$HOLD_SEC" --idle-minutes "$IDLE_MIN"
    --status-interval 5 --max-rss-mb "$MAX_RSS_MB" --max-fail-pct "$MAX_FAIL_PCT")
[[ -n "${SESSION_RESUMPTION:-}" ]] && client_args+=(--session-resumption)
[[ "$MODE" == v3 ]] && client_args+=(--mode v3)
[[ -n "$LANES" && "$MODE" == v3 ]] && client_args+=(--lanes)
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
if [[ "$MODE" == v3 && "$LANES" == 1 ]]; then
    client_summary="$(grep '^SUMMARY ' "$results/client.log" | tail -n 1)"
    server_stats="$(grep '^STAT .*admitted=' "$results/server.log" | tail -n 1)"
    closed_count="$(sed -n 's/.* closed_during_hold=\([0-9][0-9]*\).*/\1/p' <<<"$client_summary")"
    active_count="$(sed -n 's/.* lanes_active=\([0-9][0-9]*\).*/\1/p' <<<"$client_summary")"
    admitted_count="$(sed -n 's/.* admitted=\([0-9][0-9]*\).*/\1/p' <<<"$server_stats")"
    if [[ -z "$closed_count" || -z "$active_count" || -z "$admitted_count" ]]; then
        echo "FAIL lanes validation missing counters: client='$client_summary' server='$server_stats'" >&2
        exit 1
    fi
    if (( closed_count > 0 || active_count < admitted_count )); then
        echo "FAIL lanes held check: lanes_active=$active_count admitted=$admitted_count client_closed_during_hold=$closed_count" >&2
        exit 1
    fi
    echo "PASS lanes held check: lanes_active=$active_count admitted=$admitted_count client_closed_during_hold=$closed_count"
fi
exit "$client_rc"
