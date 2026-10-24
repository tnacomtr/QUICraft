#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Teleport-hop matrix over the external Fabric server: TCP vs /3 plain vs /3 with the busy batch
# timer, interleaved per netem profile and round. Every cell is one run-external-arms.sh
# invocation with --hop (fresh server container, fresh client JVM): after the initial view fill the
# client makes six 240 m teleport legs (ping-pong), and each leg's fill time is recorded.
# The arms run in the same order each round: tcp, plain, busy. Same conventions as
# run-moving-matrix.sh (manifest, deadlines, Xvfb, refusal of a dirty tree).
#
#   testkit/run-hop-matrix.sh --rounds 5
#   testkit/run-hop-matrix.sh --rounds 1 --profiles "clean" --name smoke-hop
#
# Rows land in testkit/results/<name>/ as <arm>-<profile>-hop[-busy]-run<N>.json; the summary is
# matrix-summary.md (MatrixSummarize --variants plain,busy): per-arm medians and min-max, paired
# per-round deltas against TCP, the hop table (fill median and p95 per arm) and the fail-closed /3
# gate. The runner prints the estimated wall time before starting.
#
# A failed run does not stop the matrix (each run is expensive and the rest still compares), but
# the exit status is 1 if any run or the summary fails. Existing files are never overwritten.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
# shellcheck source=lib/git-state.sh
. "$here/lib/git-state.sh"
# shellcheck source=netem/profiles.sh
. "$here/netem/profiles.sh"
. "$here/lib/deadline.sh"
trap 'stop_child' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

usage() {
    {
        echo "usage: run-hop-matrix.sh [--rounds N] [--profiles \"clean loss ...\"]"
        echo "                             [--name NAME | --out-dir DIR]"
        echo "                             [--port 25565] [--estimate-seconds S] [--no-stall-diag]"
        echo
        echo "Arms per profile and round: tcp, quic3 plain, quic3 busy (fresh server+client each)."
        echo "  --rounds            rounds, default 5"
        echo "  --profiles          default: clean loss delay delayloss reorder jitter"
        echo "  --name              results directory testkit/results/<name> (default: moving-<UTC>)"
        echo "  --out-dir           explicit results directory (must not exist)"
        echo "  --port              loopback port for the server, must be 25565 (default)"
        echo "  --estimate-seconds  per-run wall time used for the printed estimate (default: 300)"
        echo "  --no-stall-diag     do not enable core's /3 stall watchdog (on by default here)"
    } >&2
    exit "${1:-2}"
}

refuse() {
    echo "error: $*" >&2
    exit 2
}

die() {
    echo "error: $*" >&2
    exit 1
}

rounds=5
profiles="clean loss delay delayloss reorder jitter"
name="hop-$(date -u +%Y%m%dT%H%M%SZ)"
out_dir=""
hop_window_seconds=240
port=25565
estimate_seconds=300
stall_diag=true

while (($# > 0)); do
    case "$1" in
        --rounds)
            (($# >= 2)) || refuse "missing value for --rounds"
            rounds="$2"
            shift 2
            ;;
        --profiles)
            (($# >= 2)) || refuse "missing value for --profiles"
            profiles="$2"
            shift 2
            ;;
        --name)
            (($# >= 2)) || refuse "missing value for --name"
            name="$2"
            shift 2
            ;;
        --out-dir)
            (($# >= 2)) || refuse "missing value for --out-dir"
            out_dir="$2"
            shift 2
            ;;
        --port)
            (($# >= 2)) || refuse "missing value for --port"
            port="$2"
            shift 2
            ;;
        --estimate-seconds)
            (($# >= 2)) || refuse "missing value for --estimate-seconds"
            estimate_seconds="$2"
            shift 2
            ;;
        --no-stall-diag)
            stall_diag=false
            shift
            ;;
        -h | --help)
            usage 0
            ;;
        *)
            refuse "unknown argument '$1'"
            ;;
    esac
done

if ! [[ "$rounds" =~ ^[1-9][0-9]{0,3}$ ]]; then
    refuse "--rounds must be a positive integer, got '$rounds'"
fi
if ! [[ "$estimate_seconds" =~ ^[1-9][0-9]{0,5}$ ]]; then
    refuse "--estimate-seconds must be a positive integer, got '$estimate_seconds'"
fi
if ! [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]]; then
    refuse "--name must be [A-Za-z0-9._-]+, got '$name'"
fi
[[ -n "${profiles//[[:space:]]/}" ]] || refuse "empty profile list"
declare -A seen_profiles=()
for profile in $profiles; do
    [[ ! -v seen_profiles[$profile] ]] || refuse "duplicate profile '$profile'"
    seen_profiles[$profile]=1
    netem_args "$profile" >/dev/null || refuse "unknown netem profile '$profile'"
done
profile_count=0
for _ in $profiles; do
    profile_count=$((profile_count + 1))
done
arm_count=3
# QUICRAFT_HOP_NO_BUSY=1 runs only tcp and quic3 plain (candidate screening; the summary then lacks busy cells).
[[ "${QUICRAFT_HOP_NO_BUSY:-0}" == 1 ]] && arm_count=2
total_runs=$((rounds * profile_count * arm_count))
estimated=$((total_runs * estimate_seconds))

# Early failure beats failing on the first arm invocation.
tracked_status="$(git -C "$root" status --porcelain --untracked-files=no)" \
    || die "cannot read git status in $root"
if [[ -n "$tracked_status" ]]; then
    die "refusing to run from a dirty git tree; commit or stash tracked changes first"
fi
[[ -x "$here/run-external-arms.sh" ]] || die "missing $here/run-external-arms.sh"

if [[ -z "$out_dir" ]]; then
    out_dir="$here/results/$name"
fi
case "$out_dir" in
    /*) ;;
    *) refuse "--out-dir must be an absolute path, got '$out_dir'" ;;
esac
if [[ -e "$out_dir" ]]; then
    die "refusing to reuse existing results directory $out_dir"
fi
mkdir -p "$out_dir" || die "cannot create $out_dir"
out_dir="$(cd "$out_dir" && pwd)"
mkdir -p "$out_dir/runner-logs" || die "cannot create $out_dir/runner-logs"

echo "== moving matrix: $total_runs runs ($rounds round(s) x $profile_count profile(s) x $arm_count arms)"
run_budget=$(matrix_run_budget "$hop_window_seconds")
echo "== worst-case: $total_runs x ${run_budget}s = $((total_runs * run_budget))s (+${DEADLINE_OUTER_GRACE}s kill grace/run); client deadline $(client_run_budget "$hop_window_seconds")s (window + ${CLIENT_RUN_ALLOWANCE}s), build/cleanup included"
echo "== estimated wall time: $((estimated / 3600))h$(printf '%02d' $(((estimated % 3600) / 60)))m"
echo "==   (${total_runs} runs x ${estimate_seconds}s; the first arm may also build the server image)"
{
    echo "name=$name"
    echo "commit=$(git_build_commit "$root")"
    echo "rounds=$rounds"
    echo "profiles=[$profiles]"
    echo "arms=tcp,quic3-plain,quic3-busy"
    echo "hop_window_seconds=$hop_window_seconds"
    echo "port=$port"
    echo "runs=$total_runs"
    echo "estimate_seconds=$estimate_seconds"
    echo "stall_diag=$stall_diag"
    echo "started=$(date -u +%Y%m%dT%H%M%SZ)"
} >"$out_dir/environment.txt"

arm_tokens=(tcp quic3 quic3-busy)
status_file="$out_dir/runs.tsv"
printf '#rounds\t%s\n#profiles\t%s\n#arms\ttcp quic3 quic3-busy\n' "$rounds" "$profiles" >"$status_file"
failed=0
index=0
for ((round = 0; round < rounds; round++)); do
    for profile in $profiles; do
        for ((k = 0; k < arm_count; k++)); do
            index=$((index + 1))
            token="${arm_tokens[$k]}"
            case "$token" in
                tcp)
                    arm_args=(--arm tcp)
                    stem="tcp-$profile-hop-run$round"
                    ;;
                quic3)
                    arm_args=(--arm quic3)
                    stem="quic3-$profile-hop-run$round"
                    ;;
                quic3-busy)
                    arm_args=(--arm quic3 --busy-timer)
                    stem="quic3-$profile-hop-busy-run$round"
                    ;;
            esac
            common_args=(
                --netem "$profile"
                --run "$round"
                --out-dir "$out_dir"
                --hop
                --port "$port"
            )
            if [[ "$stall_diag" == true ]]; then
                common_args+=(--stall-diag)
            fi
            echo "== [$index/$total_runs] $stem ($(date -u +%H:%M:%SZ))"
            run_status=0
            bounded_outer "$run_budget" "$here/run-external-arms.sh" "${arm_args[@]}" "${common_args[@]}" \
                >"$out_dir/runner-logs/$stem.log" 2>&1 || run_status=$?
            if ((run_status == 0)); then
                printf '%s\t%s\t%s\tok\t%s.json\n' "$profile" "$token" "$round" "$stem" >>"$status_file"
            else
                failed=$((failed + 1))
                printf '%s\t%s\t%s\tfailed\t%s.json\n' "$profile" "$token" "$round" "$stem" >>"$status_file"
                echo "==   failed (status $run_status); runner log: $out_dir/runner-logs/$stem.log" >&2
            fi
        done
    done
done

bin="$root/testkit/build/install/quicraft-testkit/bin/quicraft-testkit"
# Always rebuild: a stale installed summarizer from an older commit would ignore the manifest and
# variant flags and summarize the wrong thing (gradle no-ops when it is current).
bounded 1200 "$root/gradlew" -p "$root" --no-configuration-cache -q :testkit:installDist \
    || die "could not build the matrix summarizer"

summary_status=0
bounded 120 "$bin" matrix-summarize --dir "$out_dir" --manifest "$status_file" --profiles "$profiles" \
    --variants plain,busy --markdown "$out_dir/matrix-summary.md" \
    || summary_status=$?

{
    echo "finished=$(date -u +%Y%m%dT%H%M%SZ)"
    echo "failed_runs=$failed"
    echo "summary_status=$summary_status"
} >>"$out_dir/environment.txt"

echo "moving matrix artifacts: $out_dir"
if (( failed > 0 )); then
    exit 1
fi
exit "$summary_status"
