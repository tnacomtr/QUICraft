#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs the TCP baseline: BATCHES x PROFILES, RUNS measured joins each (after WARMUP joins
# that are discarded), then checks reproducibility. Method: docs/benchmarks.md.
#
#   testkit/run-baseline.sh                       # full baseline (3 batches x 4 profiles x 20 runs)
#   BATCHES=1 RUNS=3 PROFILES=clean testkit/run-baseline.sh   # smoke test
#
# Results land in testkit/results/<UTC timestamp>/batch<k>/<profile>.json plus summary.md.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

BATCHES="${BATCHES:-3}"
RUNS="${RUNS:-20}"
WARMUP="${WARMUP:-2}"
PROFILES="${PROFILES:-clean loss delay reorder}"
ONLINE="${ONLINE:-true}"
PLAY_MS="${PLAY_MS:-10000}"
TOLERANCE_PCT="${TOLERANCE_PCT:-10}"
TOLERANCE_MS="${TOLERANCE_MS:-5}"
KEEP_UP="${KEEP_UP:-false}"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
results="$here/results/$stamp"
mkdir -p "$results"

export VELOCITY_ONLINE_MODE="$ONLINE" HOST_UID="$(id -u)" HOST_GID="$(id -g)"
compose=(docker compose -f "$here/docker/compose.yaml")

wait_for_log() { # service, pattern, timeout seconds
    local deadline=$((SECONDS + $3))
    until "${compose[@]}" logs --no-color "$1" 2>/dev/null | grep -q "$2"; do
        if ((SECONDS > deadline)); then
            echo "timed out waiting for $1" >&2
            "${compose[@]}" logs --no-color --tail 50 "$1" >&2
            exit 1
        fi
        sleep 2
    done
}

cleanup() {
    if [[ "$KEEP_UP" != true ]]; then
        "${compose[@]}" down --timeout 60 >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

"$root/gradlew" -p "$root" -q :testkit:installDist
"${compose[@]}" build --quiet
"${compose[@]}" up -d mocksession paper velocity
wait_for_log paper 'Done (' 600
wait_for_log velocity 'Done (' 120

{
    echo "started: $stamp"
    echo "batches=$BATCHES runs=$RUNS warmup=$WARMUP profiles=[$PROFILES] online=$ONLINE play_ms=$PLAY_MS"
    echo "git: $(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo none)$(git -C "$root" diff --quiet 2>/dev/null || echo ' (dirty)')"
    echo "kernel: $(uname -r)"
    echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
} > "$results/environment.txt"

online_flag=()
[[ "$ONLINE" == true ]] && online_flag=(--online)

for batch in $(seq 1 "$BATCHES"); do
    for profile in $PROFILES; do
        echo "== batch $batch/$BATCHES, profile $profile"
        "${compose[@]}" run --rm --no-deps -e NETEM_PROFILE="$profile" bench \
            bench --host velocity --port 25565 --profile "$profile" \
            --runs "$RUNS" --warmup "$WARMUP" --play-ms "$PLAY_MS" "${online_flag[@]}" \
            --out "/results/$stamp/batch$batch/$profile.json" || echo "!! bench reported failed runs"
    done
done

"$root/testkit/build/install/quicraft-testkit/bin/quicraft-testkit" summarize \
    --dir "$results" --tolerance-pct "$TOLERANCE_PCT" --tolerance-ms "$TOLERANCE_MS" \
    --markdown "$results/summary.md"
