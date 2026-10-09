#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Transport benchmark: TCP vs QUIC (Reno, CUBIC, BBR) with Minecraft-shaped traffic, under every
# netem profile, all transports interleaved in the same run. Picks the congestion-control
# default and the head start (docs/protocol.md §5, §9). Method: docs/benchmarks.md.
#
#   testkit/run-transport-bench.sh                         # ~30 min
#   RUNS=2 PROFILES=clean testkit/run-transport-bench.sh   # smoke test
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

RUNS="${RUNS:-20}"
WARMUP="${WARMUP:-1}"
PROFILES="${PROFILES:-clean loss delay reorder}"
PLAY_MS="${PLAY_MS:-3000}"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
results="$here/results/transport-$stamp"
mkdir -p "$results"
export HOST_UID="$(id -u)" HOST_GID="$(id -g)"
compose=(docker compose -f "$here/docker/compose.yaml" --profile transport)

cleanup() { "${compose[@]}" rm -sf transportserver >/dev/null 2>&1 || true; }
trap cleanup EXIT

"$root/gradlew" -p "$root" -q :testkit:installDist
"${compose[@]}" build --quiet mocksession
rm -f "$here/results/transport-fp.txt"
"${compose[@]}" up -d transportserver
for _ in $(seq 1 60); do [[ -s "$here/results/transport-fp.txt" ]] && break; sleep 1; done
[[ -s "$here/results/transport-fp.txt" ]] || { echo "transport server did not start" >&2; exit 1; }

{
    echo "started: $stamp"
    echo "runs=$RUNS warmup=$WARMUP profiles=[$PROFILES] play_ms=$PLAY_MS"
    echo "git: $(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo none)$(git -C "$root" diff --quiet 2>/dev/null || echo ' (dirty)')"
    echo "kernel: $(uname -r)"
    echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
} > "$results/environment.txt"

for profile in $PROFILES; do
    echo "== profile $profile"
    "${compose[@]}" run --rm --no-deps -e NETEM_PROFILE="$profile" bench \
        transport-client --host transportserver --port 30000 --fingerprint-file /results/transport-fp.txt \
        --runs "$RUNS" --warmup "$WARMUP" --play-ms "$PLAY_MS" --profile "$profile" \
        --out "/results/transport-$stamp/$profile.json" || echo "!! some runs failed"
done

"$root/testkit/build/install/quicraft-testkit/bin/quicraft-testkit" transport-summarize \
    --dir "$results" --markdown "$results/summary.md"
