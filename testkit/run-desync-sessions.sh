#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# No-desync play sessions under netem: for every profile, one PlaySession run over TCP and over
# /3 (plain, and with the busy batch timer), each in a fresh client JVM with the in-process
# dedicated server. The whole JVM runs in a private user+network namespace whose loopback carries
# the netem profile (testkit/netem/loopback-session.sh); see that helper for why the in-process
# path cannot use netem-attach.sh or server-netem.sh.
#
#   testkit/run-desync-sessions.sh --name train-1
#   testkit/run-desync-sessions.sh --profiles "clean delayloss" --variants plain,busy
#
# Each invocation:
#   1. refuses a dirty git tree, records HEAD and the parameters;
#   2. narrows fabric/src/gametest/resources/fabric.mod.json to DesyncSessionGameTest, with a trap
#      that restores the original byte for byte on every exit path;
#   3. pre-builds and resolves every dependency online, so each sharded run can run --offline
#      inside its network namespace (only loopback exists there);
#   4. runs one PlaySession per invocation: DesyncSessionGameTest writes one JSON row with ok and
#      the first mismatch message to testkit/results/<name>/;
#   5. writes summary.md and exits 0 only when every row passed.
#
# Requires Linux with unprivileged user namespaces, iproute2, docker is NOT needed, JDK 25, python3,
# git, flock, and a display or xvfb-run.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
# shellcheck source=lib/git-state.sh
. "$here/lib/git-state.sh"
# shellcheck source=netem/profiles.sh
. "$here/netem/profiles.sh"

entrypoint="rs.sudoe.quicraft.fabric.gametest.DesyncSessionGameTest"
module=":fabric-26x"
mod="${QUICRAFT_DESYNC_MOD_JSON:-$root/fabric/src/gametest/resources/fabric.mod.json}"
processed="$root/fabric-26x/build/resources/gametest/fabric.mod.json"
lock_file="$root/.gradle/quicraft-gametest-resources.lock"

usage() {
    {
        echo "usage: run-desync-sessions.sh [--name NAME | --out-dir DIR] [--rounds N]"
        echo "                                [--profiles \"clean loss ...\"] [--variants plain,busy]"
        echo "                                [--estimate-seconds S]"
        echo
        echo "One fresh client JVM (with its in-process dedicated server) per PlaySession run, in a"
        echo "private netns whose loopback carries the profile."
        echo "  --name              results directory testkit/results/<name> (default: desync-<UTC>)"
        echo "  --out-dir           explicit results directory (refused if it exists)"
        echo "  --rounds            rounds, one PlaySession run per arm per round (default: 1)"
        echo "  --profiles          default: clean loss delay delayloss reorder jitter slow"
        echo "  --variants          /3 variants: plain,busy (default) or plain"
        echo "  --estimate-seconds  per-run wall time used for the printed estimate (default: 240)"
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

rounds=1
profiles="clean loss delay delayloss reorder jitter slow"
variants="plain,busy"
name="desync-$(date -u +%Y%m%dT%H%M%SZ)"
out_dir=""
estimate_seconds=240

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
        --variants)
            (($# >= 2)) || refuse "missing value for --variants"
            variants="$2"
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
        --estimate-seconds)
            (($# >= 2)) || refuse "missing value for --estimate-seconds"
            estimate_seconds="$2"
            shift 2
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
for profile in $profiles; do
    netem_args "$profile" >/dev/null || refuse "unknown netem profile '$profile'"
done
case ",$variants," in
    *,plain,* | *,busy,*) ;;
    *) refuse "--variants must name plain and/or busy, got '$variants'" ;;
esac
variant_list="${variants//,/ }"
for variant in $variant_list; do
    case "$variant" in
        plain | busy) ;;
        *) refuse "--variants must be plain,busy or plain, got '$variant'" ;;
    esac
done
# The arms: tcp once, then each /3 variant. busy is /3 only by definition.
arms=(tcp)
for variant in $variant_list; do
    arms+=("quic3:$variant")
done
arm_count=${#arms[@]}
profile_count=0
for _ in $profiles; do
    profile_count=$((profile_count + 1))
done
total_runs=$((rounds * profile_count * arm_count))
estimated=$((total_runs * estimate_seconds))

command -v python3 >/dev/null 2>&1 || die "python3 is required"
command -v git >/dev/null 2>&1 || die "git is required"
command -v flock >/dev/null 2>&1 || die "flock is required to serialize gametest runners"
command -v unshare >/dev/null 2>&1 || die "unshare is required (util-linux)"
if ! command -v xvfb-run >/dev/null 2>&1 && [[ -z "${DISPLAY:-}" ]]; then
    die "no DISPLAY and no xvfb-run; the client gametest needs a display"
fi
[[ -f "$mod" ]] || die "no gametest entrypoint file at $mod"

# Provenance only means something when the tracked tree is the commit it names.
tracked_status="$(git -C "$root" status --porcelain --untracked-files=no)" \
    || die "cannot read git status in $root"
if [[ -n "$tracked_status" ]]; then
    die "refusing to run from a dirty git tree; commit or stash tracked changes first"
fi
commit="$(git -C "$root" rev-parse HEAD 2>/dev/null)" || die "cannot read HEAD in $root"
if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
    die "HEAD is not a 40-hex commit: '$commit'"
fi

mkdir -p "$root/.gradle"
exec {lock_fd}>>"$lock_file"
if ! flock -n "$lock_fd"; then
    die "another gametest runner holds $lock_file"
fi

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

echo "== desync sessions: $total_runs runs ($rounds round(s) x $profile_count profile(s) x $arm_count arms)"
echo "== estimated wall time: $((estimated / 3600))h$(printf '%02d' $(((estimated % 3600) / 60)))m"
echo "==   (${total_runs} runs x ${estimate_seconds}s; the real time depends on join and world generation)"
{
    echo "name=$name"
    echo "commit=$commit"
    echo "rounds=$rounds"
    echo "profiles=[$profiles]"
    echo "variants=[$variants]"
    echo "runs=$total_runs"
    echo "estimate_seconds=$estimate_seconds"
    echo "started=$(date -u +%Y%m%dT%H%M%SZ)"
} >"$out_dir/environment.txt"

backup=""
backup_ready=false
backup_sha=""

sha256_of() {
    python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
}

restore_mod() {
    [[ "$backup_ready" == true && -n "$backup" ]] || return 0
    if cp -p "$backup" "$mod" && cmp -s "$backup" "$mod" \
        && [[ "$(sha256_of "$mod")" == "$backup_sha" ]]; then
        rm -f "$backup"
        backup_ready=false
        echo "restored $mod byte for byte (sha256 $backup_sha)"
        return 0
    fi
    echo "ERROR: could not restore $mod byte for byte; backup kept at $backup" >&2
    return 1
}

cleanup() {
    cleanup_status=$?
    trap - EXIT
    trap '' INT TERM
    if ! restore_mod; then
        cleanup_status=1
    fi
    exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

backup="$(mktemp "${TMPDIR:-/tmp}/quicraft-desync-fabric-mod.XXXXXX")" || die "cannot create a backup file"
cp -p "$mod" "$backup" || die "cannot back up $mod"
backup_sha="$(sha256_of "$backup")" || die "cannot checksum $backup"
backup_ready=true

if ! python3 - "$mod" "$entrypoint" <<'PY'
import json
import sys

path, entrypoint = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    document = json.load(handle)
entrypoints = document.get("entrypoints") if isinstance(document, dict) else None
if not isinstance(entrypoints, dict):
    raise SystemExit(f"{path} has no entrypoints object")
entrypoints["fabric-client-gametest"] = [entrypoint]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(document, handle, indent=2)
    handle.write("\n")
PY
then
    die "could not narrow $mod to $entrypoint"
fi
echo "narrowed $mod to $entrypoint (original sha256 $backup_sha)"

# Everything the offline runs need must already be in the Gradle caches: inside the netns only
# loopback exists, so a missing artifact would fail every run after the first.
echo "== pre-building (online) and resolving the gametest runtime classpath"
if ! "$root/gradlew" -p "$root" --no-daemon -q --console=plain \
    :fabric-26x:downloadAssets :fabric-26x:compileClientJava :fabric-26x:compileGametestJava \
    :testkit:installDist >"$out_dir/build.log" 2>&1; then
    tail -n 40 "$out_dir/build.log" >&2 || true
    die "the pre-build failed (log: $out_dir/build.log)"
fi
if ! "$root/gradlew" -p "$root" --no-daemon --console=plain \
    :fabric-26x:dependencies --configuration gametestRuntimeClasspath \
    >"$out_dir/dependencies.log" 2>&1; then
    tail -n 40 "$out_dir/dependencies.log" >&2 || true
    die "could not resolve the gametest runtime classpath (log: $out_dir/dependencies.log)"
fi
if ! "$root/gradlew" -p "$root" --no-daemon -q --console=plain \
    :fabric-26x:processGametestResources --rerun-tasks >>"$out_dir/build.log" 2>&1; then
    tail -n 40 "$out_dir/build.log" >&2 || true
    die "the forced :fabric-26x:processGametestResources build failed"
fi
if [[ -f "$processed" ]]; then
    python3 - "$processed" "$entrypoint" <<'PY' || die "the processed gametest resource is not narrowed"
import json
import sys

path, entrypoint = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    document = json.load(handle)
entrypoints = document.get("entrypoints") if isinstance(document, dict) else None
listed = entrypoints.get("fabric-client-gametest") if isinstance(entrypoints, dict) else None
if listed != [entrypoint]:
    raise SystemExit(f"processed entrypoint list is {listed!r}, not [{entrypoint!r}]")
PY
fi

xvfb=()
if [[ -z "${DISPLAY:-}" ]]; then
    xvfb=(xvfb-run -a -s "-screen 0 1280x720x24")
fi

status_file="$out_dir/runs.tsv"
: >"$status_file"
failed=0
missing=0
index=0
for ((round = 0; round < rounds; round++)); do
    for profile in $profiles; do
        for arm in "${arms[@]}"; do
            index=$((index + 1))
            transport="${arm%%:*}"
            variant="plain"
            if [[ "$arm" == *:* ]]; then
                variant="${arm##*:}"
            fi
            stem="$profile-$transport-$variant-run$round"
            row="$out_dir/$stem.json"
            client_log="$out_dir/$stem.client.log"
            netem_log="$out_dir/$stem.netem.txt"
            echo "== [$index/$total_runs] $stem ($(date -u +%H:%M:%SZ))"
            props=(
                "-Pquicraft.desync=true"
                "-Pquicraft.desyncOut=$row"
                "-Pquicraft.desyncTransport=$transport"
                "-Pquicraft.desyncVariant=$variant"
                "-Pquicraft.desyncNetem=$profile"
                "-Pquicraft.desyncRun=$round"
                "-Pquicraft.desyncCommit=$commit"
            )
            run_status=0
            ALSOFT_DRIVERS=null "${xvfb[@]}" \
                "$here/netem/loopback-session.sh" --profile "$profile" -- \
                "$root/gradlew" -p "$root" --no-daemon --offline --no-configuration-cache --console=plain \
                "${props[@]}" "$module:runClientGameTest" \
                >"$client_log" 2>"$netem_log" || run_status=$?
            result="$(python3 - "$row" <<'PY'
import json
import sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
    row = document["runs"][0]
except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
    print(f"missing\tno readable row ({error})")
else:
    status = "ok" if row.get("ok") is True else "failed"
    error = row.get("error")
    print(f"{status}\t{'' if error is None else str(error).replace(chr(9), ' ')}")
PY
)" || result="missing	the row could not be read"
            result_status="${result%%$'\t'*}"
            result_error="${result#*$'\t'}"
            if [[ "$result_status" == failed ]]; then
                failed=$((failed + 1))
            elif [[ "$result_status" != ok ]]; then
                missing=$((missing + 1))
            fi
            printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$round" "$profile" "$transport" "$variant" \
                "$result_status" "$result_error" >>"$status_file"
            echo "==   $result_status${result_error:+: $result_error}"
            if [[ "$result_status" != ok ]]; then
                echo "==   client log: $client_log (runClientGameTest status $run_status, netem notes $netem_log)" >&2
            fi
        done
    done
done

bin="$root/testkit/build/install/quicraft-testkit/bin/quicraft-testkit"
if [[ ! -x "$bin" ]]; then
    "$root/gradlew" -p "$root" -q :testkit:installDist
fi

summary_status=0
python3 - "$status_file" "$out_dir/summary.md" <<'PY' || summary_status=$?
import collections
import sys

status_path, out_path = sys.argv[1], sys.argv[2]
rows = []
with open(status_path, encoding="utf-8") as handle:
    for line in handle:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 6:
            rows.append(parts)
groups = collections.OrderedDict()
for round_index, profile, transport, variant, status, error in rows:
    groups.setdefault((profile, transport, variant), []).append((status, error))
lines = ["# Desync sessions", "", "| Profile | Transport | Variant | Runs | Passed | Failed | Missing | First mismatch |",
         "| --- | --- | --- | --- | --- | --- | --- | --- |"]
failed = 0
for (profile, transport, variant), entries in groups.items():
    passed = sum(1 for status, _ in entries if status == "ok")
    bad = sum(1 for status, _ in entries if status == "failed")
    miss = sum(1 for status, _ in entries if status not in ("ok", "failed"))
    failed += bad + miss
    first = next((error for status, error in entries if status != "ok" and error), "")
    lines.append(f"| {profile} | {transport} | {variant} | {len(entries)} | {passed} | {bad} | {miss} | {first} |")
lines.append("")
lines.append(f"Runs: {len(rows)}, failed: {failed}")
with open(out_path, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines) + "\n")
print("\n".join(lines))
raise SystemExit(1 if failed else 0)
PY
if (( summary_status != 0 )); then
    failed=$((failed + 1))
fi

{
    echo "finished=$(date -u +%Y%m%dT%H%M%SZ)"
    echo "failed=$failed"
    echo "missing=$missing"
    echo "summary=$out_dir/summary.md"
} >>"$out_dir/environment.txt"

echo "desync session artifacts: $out_dir"
if (( failed > 0 || missing > 0 )); then
    exit 1
fi
exit 0
