#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# One external arm run (plan: .agents/plans/2026-10-20-external-arms.md, unit U2): a fresh
# Fabric server container plus a fresh client JVM, for exactly one arm `tcp`, `quic2` or `quic3`.
#
#   testkit/run-external-arms.sh --arm quic3 --run 0 --out-dir /tmp/opencode/arms
#   testkit/run-external-arms.sh --arm tcp --run 0 --warmup --port 25565 --out-dir ./arms
#
# Each invocation:
#   1. refuses a dirty git tree and records HEAD as the 40-hex build commit;
#   2. starts ONLY `fabric` and `mocksession` from docker/compose.yaml plus
#      docker/compose.real-fabric.yaml, in a fresh unique Compose project, waits for the loopback
#      TCP port, and dumps the fabric container's last 80 log lines if it never comes up;
#   3. narrows fabric/src/gametest/resources/fabric.mod.json to
#      ExternalArmGameTest, with a trap that restores the original byte for byte on every exit
#      path (verified against a sha256 recorded before the edit);
#   4. runs :fabric-26x:runClientGameTest with the frozen -Pquicraft.arm* properties;
#   5. tears down only that unique Compose project (`down -v --remove-orphans -p <name>`), even
#      when the run fails; it never touches other projects, images, host qdisc, routes or firewall;
#   6. succeeds only when the emitted document exists, parses as JSON, and runs[0].ok is true.
#
# The emitter (ExternalArmGameTest) is a separate unit; this runner only uses the frozen property
# names. `--netem` takes clean, loss, delay, delayloss, reorder or jitter; testkit/netem-attach.sh
# shapes the server container's interface; clean attaches nothing (netem-attach reports no qdisc).
#
# Per-run documents are merged with testkit/merge-arm-samples.py. For `/3`, the merged document
# must additionally pass the testkit validator (see testkit/README.md):
#   ./gradlew :testkit:installDist
#   testkit/build/install/quicraft-testkit/bin/quicraft-testkit v3-samples --in <merged.json>
#
# Environment knobs (defaults in parentheses):
#   QUICRAFT_ARM_MOD_JSON       entrypoint file to narrow (the real gametest fabric.mod.json)
#   QUICRAFT_ARM_READY_TIMEOUT  seconds to wait for the server's TCP port (180)
#
# Requires: docker with Compose v2, JDK 25, python3, git, flock, and a display or xvfb-run.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
mod="${QUICRAFT_ARM_MOD_JSON:-$root/fabric/src/gametest/resources/fabric.mod.json}"
entrypoint="rs.sudoe.quicraft.fabric.gametest.ExternalArmGameTest"
module=":fabric-26x"
processed="$root/fabric-26x/build/resources/gametest/fabric.mod.json"
compose_files=("$here/docker/compose.yaml" "$here/docker/compose.real-fabric.yaml")
lock_file="$root/.gradle/quicraft-gametest-resources.lock"
ready_timeout="${QUICRAFT_ARM_READY_TIMEOUT:-180}"

usage() {
    {
        echo "usage: run-external-arms.sh --arm tcp|quic2|quic3 --run N [--warmup] --out-dir DIR"
        echo "                              [--port 25565] [--play-seconds S] [--netem PROFILE]"
        echo
        echo "One fresh Fabric server container and one fresh client JVM per invocation."
        echo "  --arm           tcp | quic2 | quic3 (required)"
        echo "  --run           run index, integer >= 0 (required)"
        echo "  --warmup        mark this run as a warm-up (default: false)"
        echo "  --out-dir       directory for <arm>-<netem>-run<N>.json (required, must not exist yet)"
        echo "  --port          loopback TCP+UDP port; must be 25565, the port the server advertises"
        echo "  --play-seconds  measured play time (default: 20)"
        echo "  --netem         clean (default) | loss | delay | delayloss | reorder | jitter"
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

arm=""
run_index=""
out_dir=""
warmup=false
port="25565"
play_seconds="20"
netem="clean"

while (($# > 0)); do
    case "$1" in
        --arm)
            (($# >= 2)) || refuse "missing value for --arm"
            arm="$2"
            shift 2
            ;;
        --run)
            (($# >= 2)) || refuse "missing value for --run"
            run_index="$2"
            shift 2
            ;;
        --warmup)
            warmup=true
            shift
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
        --play-seconds)
            (($# >= 2)) || refuse "missing value for --play-seconds"
            play_seconds="$2"
            shift 2
            ;;
        --netem)
            (($# >= 2)) || refuse "missing value for --netem"
            netem="$2"
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

[[ -n "$arm" ]] || refuse "missing required --arm"
case "$arm" in
    tcp | quic2 | quic3) ;;
    *) refuse "--arm must be tcp, quic2 or quic3, got '$arm'" ;;
esac
[[ -n "$run_index" ]] || refuse "missing required --run"
if ! [[ "$run_index" =~ ^(0|[1-9][0-9]{0,8})$ ]]; then
    refuse "--run must be a non-negative integer, got '$run_index'"
fi
[[ -n "$out_dir" ]] || refuse "missing required --out-dir"
if ! [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] || ((10#$port > 65535)); then
    refuse "--port must be an integer in 1..65535, got '$port'"
fi
if ! [[ "$play_seconds" =~ ^[1-9][0-9]{0,6}$ ]]; then
    refuse "--play-seconds must be a positive integer, got '$play_seconds'"
fi
case "$netem" in
    clean | loss | delay | delayloss | reorder | jitter) ;;
    *) refuse "--netem must be clean, loss, delay, delayloss, reorder or jitter, got '$netem'" ;;
esac
# The container advertises its internal port 25565 for QUIC; the client dials the advertised port
# on the host it resolved, so the host mapping must be the same number.
if [[ "$port" != 25565 ]]; then
    refuse "--port must be 25565 (the server advertises its internal port 25565), got '$port'"
fi
if ! [[ "$ready_timeout" =~ ^[1-9][0-9]{0,5}$ ]]; then
    refuse "QUICRAFT_ARM_READY_TIMEOUT must be a positive number of seconds, got '$ready_timeout'"
fi
warmup_value=false
if [[ "$warmup" == true ]]; then
    warmup_value=true
fi

command -v docker >/dev/null 2>&1 || die "docker is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
command -v git >/dev/null 2>&1 || die "git is required"
command -v flock >/dev/null 2>&1 || die "flock is required to serialize gametest runners"
for file in "${compose_files[@]}"; do
    [[ -f "$file" ]] || die "missing compose file $file"
done
[[ -f "$mod" ]] || die "no gametest entrypoint file at $mod"
if ! command -v xvfb-run >/dev/null 2>&1 && [[ -z "${DISPLAY:-}" ]]; then
    die "no DISPLAY and no xvfb-run; the client gametest needs a display"
fi

# A build provenance commit only means something when the tracked tree is the commit it names.
# Untracked files are ignored, as in testkit/lib/git-state.sh: they do not change the compiled jar.
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

if [[ -e "$out_dir" && ! -d "$out_dir" ]]; then
    die "--out-dir $out_dir is not a directory"
fi
mkdir -p "$out_dir" || die "cannot create --out-dir $out_dir"
out_dir="$(cd "$out_dir" && pwd)"
out="$out_dir/$arm-$netem-run$run_index.json"
# Every side file of this run (status, netem, client log, moved config) shares the prefix: refuse
# when any of them already exists, so evidence is never overwritten or nested into.
for existing in "$out_dir/$arm-$netem-run$run_index".*; do
    if [[ -e "$existing" || -L "$existing" ]]; then
        die "refusing to overwrite existing $existing"
    fi
done

suffix="$(python3 -c 'import secrets; print(secrets.token_hex(6))')" \
    || die "could not generate a unique Compose project suffix"
if ! [[ "$suffix" =~ ^[0-9a-f]{12}$ ]]; then
    die "could not generate a valid Compose project suffix: '$suffix'"
fi
project="quicraft-arm-$arm-$run_index-$suffix"
# --profile fabric: without it `down` silently skips the profiled fabric service.
compose=(docker compose -f "${compose_files[0]}" -f "${compose_files[1]}" -p "$project" --profile fabric)

backup=""
backup_ready=false
backup_sha=""
project_started=false
client_log=""
keep_client_log=false

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
    if [[ "$project_started" == true ]]; then
        if "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1; then
            echo "removed Compose project $project"
        else
            echo "ERROR: could not remove Compose project $project; run: docker compose -f ${compose_files[0]} -f ${compose_files[1]} -p $project down -v --remove-orphans" >&2
            cleanup_status=1
        fi
    fi
    if [[ -n "$client_log" && -f "$client_log" ]]; then
        if ((cleanup_status == 0)) && [[ "$keep_client_log" != true ]]; then
            rm -f "$client_log"
        else
            echo "client log kept at $client_log" >&2
        fi
    fi
    exit "$cleanup_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Record the original, then narrow to the one emitter. The trap restores it on every exit path.
backup="$(mktemp "${TMPDIR:-/tmp}/quicraft-arm-fabric-mod.XXXXXX")" || die "cannot create a backup file"
cp -p "$mod" "$backup" || die "cannot back up $mod"
backup_sha="$(sha256_of "$backup")" || die "cannot checksum $backup"
backup_ready=true

if ! python3 - "$mod" "$entrypoint" <<'PY'
import json
import sys

path, entrypoint = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    document = json.load(handle)
if not isinstance(document, dict):
    raise SystemExit(f"{path} is not a JSON object")
entrypoints = document.get("entrypoints")
if not isinstance(entrypoints, dict):
    raise SystemExit(f"{path} has no entrypoints object")
listed = entrypoints.get("fabric-client-gametest")
if not isinstance(listed, list):
    raise SystemExit(f"{path} has no fabric-client-gametest entrypoint list")
# ExternalArmGameTest is deliberately NOT part of the committed default list; it is added here
# for this one invocation and the trap restores the original file.
entrypoints["fabric-client-gametest"] = [entrypoint]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(document, handle, indent=2)
    handle.write("\n")
with open(path, encoding="utf-8") as handle:
    narrowed = json.load(handle)
if narrowed["entrypoints"]["fabric-client-gametest"] != [entrypoint]:
    raise SystemExit("entrypoint narrowing did not round-trip")
PY
then
    die "could not narrow $mod to $entrypoint"
fi
echo "narrowed $mod to $entrypoint (original sha256 $backup_sha)"

echo "== build :fabric-26x:testkitMod (image build context, commit $commit)"
"$root/gradlew" -p "$root" -q :fabric-26x:testkitMod --console=plain \
    || die "the :fabric-26x:testkitMod build failed"

# The base service reads FABRIC_ONLINE_MODE; the real-fabric overlay templates
# REAL_FABRIC_ONLINE_MODE and REAL_FABRIC_MULTISTREAM. Set all of them so neither file can
# silently fall back to its default.
export FABRIC_ONLINE_MODE=false REAL_FABRIC_ONLINE_MODE=false
# A /3 listener is /3-only (QuicServer: a /2 client fails the ALPN), so the /2 arm needs the same
# build with multistream off; tcp and quic3 run against the /3 server.
server_multistream=true
if [[ "$arm" == quic2 ]]; then
    server_multistream=false
fi
export REAL_FABRIC_MULTISTREAM="$server_multistream" REAL_FABRIC_PORT="$port"
echo "== compose up ($project): fabric mocksession"
project_started=true
if ! "${compose[@]}" up -d --build fabric mocksession; then
    die "compose up failed for project $project"
fi

# Docker's port proxy accepts TCP before the server listens, so readiness is a real status ping
# that carries the QUIC advertisement.
echo "== waiting for a status response with the QUIC advertisement on 127.0.0.1:$port (up to ${ready_timeout}s)"
ready=false
deadline=$((SECONDS + ready_timeout))
while ((SECONDS < deadline)); do
    if status_json="$(python3 -I "$here/status-ping.py" 127.0.0.1 "$port" 3 2>/dev/null)" \
        && [[ "$status_json" == *'"quicraft:quic"'* ]]; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    echo "error: the fabric server did not accept TCP on 127.0.0.1:$port within ${ready_timeout}s" >&2
    echo "== last 80 fabric log lines:" >&2
    "${compose[@]}" logs --no-color --tail 80 fabric >&2 || true
    exit 1
fi
echo "server is ready: $status_json"
printf '%s\n' "$status_json" >"$out_dir/$arm-$netem-run$run_index.status.json"

# netem on the server's own namespace. netem-attach runs for every profile, but clean installs no
# qdisc, IFB or filter (its status says so); shaped profiles' filter counters prove the game flows
# were classified.
echo "== netem $netem on $project"
"$here/netem-attach.sh" apply --project "$project" --profile "$netem" \
    >"$out_dir/$arm-$netem-run$run_index.netem-apply.txt" 2>&1 \
    || { cat "$out_dir/$arm-$netem-run$run_index.netem-apply.txt" >&2; die "netem-attach apply failed"; }

# A fresh container has a fresh certificate: an old pin would read as CHANGED and /3 offline
# refuses that. Move the client's previous config aside into the evidence (never delete it).
client_config="$root/fabric-26x/build/run/clientGameTest/config/quicraft"
if [[ -e "$client_config" ]]; then
    mv -T -n "$client_config" "$out_dir/$arm-$netem-run$run_index.client-config-before" \
        || die "cannot move the previous client config aside"
fi

echo "== :fabric-26x:processGametestResources (forced, so the narrowed entrypoint list is used)"
if ! "$root/gradlew" -p "$root" :fabric-26x:processGametestResources --rerun-tasks --console=plain; then
    die "the forced :fabric-26x:processGametestResources build failed"
fi
if [[ -f "$processed" ]]; then
    if ! python3 - "$processed" "$entrypoint" <<'PY'
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
    then
        die "the processed gametest resource at $processed is not narrowed"
    fi
fi

client_log="$out_dir/$arm-$netem-run$run_index.client.log"
keep_client_log=true
xvfb=()
if [[ -z "${DISPLAY:-}" ]]; then
    xvfb=(xvfb-run -a -s "-screen 0 1280x720x24")
fi
client=(
    "$root/gradlew" -p "$root" --no-daemon --console=plain
    "-Pquicraft.arm=$arm"
    "-Pquicraft.armServer=127.0.0.1:$port"
    "-Pquicraft.armOut=$out"
    "-Pquicraft.armNetem=$netem"
    "-Pquicraft.armRun=$run_index"
    "-Pquicraft.armWarmup=$warmup_value"
    "-Pquicraft.armCommit=$commit"
    "-Pquicraft.armPlaySeconds=$play_seconds"
    "$module:runClientGameTest"
)
echo "== client: ${client[*]}"
echo "== client: ${client[*]}" >>"$client_log"
client_status=0
ALSOFT_DRIVERS=null "${xvfb[@]}" "${client[@]}" >>"$client_log" 2>&1 || client_status=$?
if ((client_status != 0)); then
    keep_client_log=true
    echo "WARNING: runClientGameTest exited with status $client_status; checking the emitted row anyway (log: $client_log)" >&2
fi

"$here/netem-attach.sh" status --project "$project" --profile "$netem" \
    >"$out_dir/$arm-$netem-run$run_index.netem-status.txt" 2>&1 \
    || echo "WARNING: netem-attach status failed; see $out_dir/$arm-$netem-run$run_index.netem-status.txt" >&2

if [[ ! -f "$out" ]]; then
    echo "error: the client wrote no sample at $out (runClientGameTest status $client_status)" >&2
    tail -n 40 "$client_log" >&2 || true
    exit 1
fi

if ! python3 - "$out" "$arm" <<'PY'
import json
import sys

path, arm = sys.argv[1], sys.argv[2]
try:
    with open(path, encoding="utf-8") as handle:
        document = json.load(handle)
except (OSError, ValueError) as error:
    raise SystemExit(f"{path} is not readable JSON: {error}")
if not isinstance(document, dict):
    raise SystemExit(f"{path} is not a JSON object")
if document.get("arm") != arm:
    raise SystemExit(f"{path} names arm {document.get('arm')!r}, expected {arm!r}")
runs = document.get("runs")
if not isinstance(runs, list) or not runs:
    raise SystemExit(f"{path} has no runs[0] row")
row = runs[0]
if not isinstance(row, dict):
    raise SystemExit(f"{path} runs[0] is not a JSON object")
if row.get("ok") is not True:
    raise SystemExit(f"{path} runs[0].ok is not true: {row.get('error')!r}")
print(f"{path}: runs[0].ok=true")
PY
then
    echo "error: $out is not a successful $arm sample" >&2
    tail -n 40 "$client_log" >&2 || true
    exit 1
fi

if ((client_status != 0)); then
    echo "WARNING: accepted the row although runClientGameTest exited with status $client_status (shutdown hang)" >&2
fi
echo "OK: $arm run $run_index wrote $out"
