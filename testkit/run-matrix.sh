#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Six-profile, transport-neutral matrix: TCP vs QUIC /2 over the netem profiles defined in
# testkit/netem/profiles.sh. Measurement and container cleanup stay in run-baseline.sh; this
# script only fixes the matrix parameters, captures the artifacts in one directory and asks
# matrix-summarize for the comparison rows.
#
# The /3 arm is deliberately not started: there is no negotiated /3 game driver yet (U3). The
# summarizer still emits a /3 cell for every profile and metric, and that cell stays fail-closed.
# Do not add quic-3 to TRANSPORTS here before the driver can supply negotiated evidence.
#
#   testkit/run-matrix.sh
#   BATCHES=1 RUNS=3 RUNS_loss=3 RUNS_delayloss=3 RUNS_reorder=3 RUNS_jitter=3 \
#       PROFILES="clean loss" testkit/run-matrix.sh          # smoke test
#
# Phase 0 parameters are preserved: BATCHES=3; N=20 for clean and delay, N=80 for every impaired
# profile; 2 warm-ups; tolerance max(10%, 5 ms); zero disconnects.
#
# Artifacts: testkit/results/matrix-<UTC timestamp>/ with environment.txt, matrix-environment.txt,
# run-baseline.log, the Phase 0 summary.md, matrix-summary.md and matrix-status.txt. Artifacts are
# kept on every exit; server containers are cleaned up by run-baseline.sh.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

PROFILES="${PROFILES:-clean loss delay delayloss reorder jitter}"
TRANSPORTS="${TRANSPORTS:-tcp quic}"
TARGET="${TARGET:-velocity}"
BATCHES="${BATCHES:-3}"
RUNS="${RUNS:-20}"
RUNS_loss="${RUNS_loss:-80}"
RUNS_delayloss="${RUNS_delayloss:-80}"
RUNS_reorder="${RUNS_reorder:-80}"
RUNS_jitter="${RUNS_jitter:-80}"
WARMUP="${WARMUP:-2}"
TOLERANCE_PCT="${TOLERANCE_PCT:-10}"
TOLERANCE_MS="${TOLERANCE_MS:-5}"

if [[ -z "${PROFILES// /}" ]]; then
    echo "PROFILES must name at least one netem profile" >&2
    exit 2
fi

# shellcheck source=netem/profiles.sh
. "$here/netem/profiles.sh"
for profile in $PROFILES; do
    if ! netem_args "$profile" >/dev/null; then
        echo "unknown netem profile: $profile" >&2
        exit 2
    fi
done

# The matrix is a comparison of exactly these two current arms. The /3 arm waits on U3 and must
# not be smuggled in as another /2 label.
case "$TRANSPORTS" in
    "tcp quic" | "quic tcp") ;;
    *)
        echo "matrix TRANSPORTS must be exactly 'tcp quic'; quic-3 has no driver yet (U3)" >&2
        exit 2
        ;;
esac

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
run_dir="${MATRIX_RESULTS_DIR:-$here/results/matrix-$stamp}"
case "$run_dir" in
    "$here"/results/*) ;;
    *) echo "MATRIX_RESULTS_DIR must be under $here/results" >&2; exit 2 ;;
esac
if [[ -e "$run_dir" ]]; then
    echo "refusing to reuse existing matrix directory: $run_dir" >&2
    exit 2
fi
mkdir -p "$run_dir"

build_commit="$(git -C "$root" rev-parse HEAD 2>/dev/null || echo unknown)"
if [[ "$build_commit" != unknown ]] && ! git -C "$root" diff --quiet 2>/dev/null; then
    build_commit="$build_commit-dirty"
fi

baseline_status="not-run"
matrix_status="not-run"
cleanup() {
    local status=$?
    {
        echo "finished: $(date -u +%Y%m%dT%H%M%SZ)"
        echo "exit: $status"
        echo "baseline_status: $baseline_status"
        echo "matrix_status: $matrix_status"
        echo "artifacts: $run_dir"
        echo "v3: not run; no negotiated /3 game driver (U3)"
    } > "$run_dir/matrix-status.txt"
    echo "matrix artifacts: $run_dir"
}
trap cleanup EXIT

{
    echo "started: $stamp"
    echo "target=$TARGET batches=$BATCHES runs=$RUNS runs_loss=$RUNS_loss runs_delayloss=$RUNS_delayloss runs_reorder=$RUNS_reorder runs_jitter=$RUNS_jitter warmup=$WARMUP"
    echo "profiles=[$PROFILES] transports=[$TRANSPORTS] tolerance=max(${TOLERANCE_PCT}%,${TOLERANCE_MS}ms)"
    echo "build: $build_commit"
    echo "kernel: $(uname -r)"
    echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
    echo "v3: not run; no negotiated /3 game driver (U3); matrix-summarize must report fail-closed"
} > "$run_dir/matrix-environment.txt"

export BATCHES RUNS RUNS_loss RUNS_delayloss RUNS_reorder RUNS_jitter WARMUP
export TOLERANCE_PCT TOLERANCE_MS PROFILES TRANSPORTS TARGET
export RESULTS_DIR="$run_dir"

set +e
"$here/run-baseline.sh" 2>&1 | tee "$run_dir/run-baseline.log"
baseline_status=${PIPESTATUS[0]}
set -e

bin="$root/testkit/build/install/quicraft-testkit/bin/quicraft-testkit"
if [[ ! -x "$bin" ]]; then
    "$root/gradlew" -p "$root" -q :testkit:installDist
fi

set +e
"$bin" matrix-summarize --dir "$run_dir" --profiles "$PROFILES" \
    --markdown "$run_dir/matrix-summary.md"
matrix_status=$?
set -e

if (( matrix_status == 2 )); then
    echo "matrix: /3 rows are fail-closed and not run yet; the U3 driver must add negotiated evidence" >&2
fi

if (( baseline_status != 0 )); then
    exit "$baseline_status"
fi
exit "$matrix_status"
