#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# W3a runtime login proof on 26.x: runs ONLY CausalLoginGameTest and fails unless the probe really ran.
# The default entrypoint list in fabric/src/gametest/resources/fabric.mod.json is restored on exit,
# including a failed or interrupted run; the test is never committed as a default entrypoint.
#
#   testkit/run-causal-login.sh
#   CAUSAL_LOGIN_CAPTURE_LOG=/tmp/opencode/causal-login-capture.log testkit/run-causal-login.sh
#
# Editing the source file alone is not enough: the resource task can be up to date, so the processed
# fabric.mod.json keeps the full list and the client runs another test with no U3 rows. This script
# rewrites the source, forces :fabric-26x:processGametestResources --rerun-tasks, then runs
# :fabric-26x:runClientGameTest. Success needs both evidence lines: the log contains CAUSAL-LOGIN
# (the probe wrote its report) and the pass-2 summary starts with a COMPLETED run whose start stamp
# belongs to this execution (the summary is newer than the run marker).
#
# The client is launched in its own session, so this invocation owns exactly the processes it
# started and nothing else. The session leader is a supervisor: it records its own pid, process
# group and /proc starttime, starts the client, records the client process and the group it
# inherited, waits for the client, records its status, and then holds the session open until this
# script stops it. The identity therefore outlives the client, so cleanup still owns the group after
# the Gradle or game JVM has exited. A stop is a TERM to that one group, ten seconds, then KILL. The
# group is checked again after TERM and KILLed even when the client or its supervisor has exited, and
# a survivor is reported. The script never scans /proc for a dev client, so a concurrent client in
# this worktree or a similarly named process is never a candidate, and it never signals a group whose
# leader it cannot verify.
#
# The client invocation passes --no-daemon. A reused Gradle daemon would fork the game JVM outside
# this session, where the recorded identity cannot reach it; the resource task keeps the daemon.
# --no-daemon keeps the Gradle build in the launcher process, a child of the supervisor, so the
# GameTest JVM it forks inherits the owned process group. The supervisor records the client command
# and its group, and the run fails closed unless that process is verified inside the owned group
# before the evidence is read.
#
# The client limit (CAUSAL_LOGIN_CLIENT_TIMEOUT, 600s by default, below the tester's outer 900s) is enforced
# here for the same reason: at the deadline the recorded group is sent TERM and then KILL, instead
# of waiting for a client that may never exit. The client is known to hang at "Stopping!" after
# writing its report. A client that exits nonzero or hits that limit is still accepted when the
# evidence above is complete and fresh: the acceptance prints a shutdownHang=true provenance warning
# instead of silently passing. Missing or stale evidence still fails closed.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
mod="$root/fabric/src/gametest/resources/fabric.mod.json"
summary="$root/fabric-26x/build/causal-login/causal-login-summary.txt"
report_dir="$root/fabric-26x/build/causal-login"
processed="$root/fabric-26x/build/resources/gametest/fabric.mod.json"
entrypoint="rs.sudoe.quicraft.fabric.gametest.CausalLoginGameTest"
log="${CAUSAL_LOGIN_CAPTURE_LOG:-/tmp/opencode/causal-login-capture.log}"
client_timeout="${CAUSAL_LOGIN_CLIENT_TIMEOUT:-600}"

if [[ ! "$client_timeout" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: CAUSAL_LOGIN_CLIENT_TIMEOUT must be a positive number of seconds, got: $client_timeout" >&2
    exit 1
fi
if ! command -v setsid >/dev/null 2>&1; then
    echo "error: setsid(1) is needed to run the client in its own session" >&2
    exit 1
fi
if [[ ! -r /proc/self/stat ]]; then
    echo "error: /proc is needed to record the client process identity" >&2
    exit 1
fi

if [[ ! -f "$mod" ]]; then
    echo "error: no gametest entrypoint file at $mod" >&2
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "error: python3 is needed to edit $mod as JSON" >&2
    exit 1
fi

xvfb=()
if [[ -z "${DISPLAY:-}" ]]; then
    if command -v xvfb-run >/dev/null 2>&1; then
        xvfb=(xvfb-run -a -s "-screen 0 1280x720x24")
    else
        echo "error: no DISPLAY and no xvfb-run; the client gametest needs a display" >&2
        exit 1
    fi
fi

mkdir -p "$(dirname "$log")"
backup="$(mktemp "${TMPDIR:-/tmp}/quicraft-causal-login-fabric-mod.XXXXXX")"
marker=""
leader_file=""
client_file=""
client_status_file=""
backup_ready=false
client_active=false
client_job=""
leader_pid=""
leader_pgid=""
leader_start=""
client_pid=""
client_pgid=""

# /proc/<pid>/stat with the field numbers of proc(5). The comm field (2) may contain spaces and
# parentheses, so the text up to the last ')' is dropped and the remaining fields are numbered from
# field 3.
proc_stat_field() {
    local pid="$1" field="$2" stat index
    local -a fields
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
    [[ -r "/proc/$pid/stat" ]] || return 1
    stat="$(<"/proc/$pid/stat")" || return 1
    stat="${stat##*)}"
    read -r -a fields <<<"$stat"
    index=$((field - 3))
    ((index >= 0 && index < ${#fields[@]})) || return 1
    printf '%s\n' "${fields[index]}"
}

# A pid plus its starttime names one process for as long as that process lives, so a reused pid never
# matches. Every signal below is preceded by this check.
leader_matches() {
    local pid="$1" start="$2" current
    [[ -n "$pid" && -n "$start" ]] || return 1
    current="$(proc_stat_field "$pid" 22)" || return 1
    [[ "$current" == "$start" ]]
}

# The same identity, once the process has not exited: a zombie is a process this run is done with.
leader_running() {
    local pid="$1" start="$2" state
    leader_matches "$pid" "$start" || return 1
    state="$(proc_stat_field "$pid" 3)" || return 1
    [[ "$state" != Z && "$state" != X ]]
}

# True while at least one process remains in the group. This is a signal syscall to the group id, not
# a /proc lookup, so no unrelated process is examined.
group_alive() {
    local pgid="$1"
    [[ "$pgid" =~ ^[1-9][0-9]*$ ]] || return 1
    kill -0 -- "-$pgid" 2>/dev/null
}

# Stops exactly the session this script launched. The recorded supervisor must still be that process
# (pid and starttime), must lead its own group, and that group must not be this script's own group,
# so the signal can only reach processes this invocation started. TERM to the group, ten seconds,
# then check the group itself: the supervisor exits on TERM, but a client or game descendant may
# ignore it, and that descendant keeps the group alive after its leader is gone. Such a group is
# KILLed and checked again; any process still there is reported.
stop_client_group() {
    local leader="$1" pgid="$2" start="$3" waited
    if [[ ! "$leader" =~ ^[1-9][0-9]*$ || ! "$pgid" =~ ^[1-9][0-9]*$ || ! "$start" =~ ^[0-9]+$ ]]; then
        echo "no usable client process identity was recorded; no process was signalled" >&2
        return 0
    fi
    if [[ "$pgid" != "$leader" ]]; then
        echo "client supervisor $leader is not its own group leader (pgid $pgid); not signalling" >&2
        return 0
    fi
    if [[ "$pgid" == "$script_pgid" ]]; then
        echo "client group $pgid is this script's own group; not signalling" >&2
        return 0
    fi
    if ! leader_running "$leader" "$start"; then
        echo "this run's client supervisor $leader had already exited; no process group was signalled"
        return 0
    fi
    echo "stopping this run's client process group $pgid (supervisor $leader)"
    kill -TERM -- "-$pgid" 2>/dev/null || true
    for waited in {1..10}; do
        sleep 1
        if ! group_alive "$pgid"; then
            echo "this run's client process group $pgid is empty after TERM (${waited}s)"
            return 0
        fi
    done
    # The verified group still has members, even though its supervisor may already be gone: a client
    # that ignored TERM keeps the group id valid, so the group is the target, not the dead leader.
    echo "this run's client process group $pgid still has processes after TERM; sending KILL"
    kill -KILL -- "-$pgid" 2>/dev/null || true
    for waited in {1..5}; do
        sleep 1
        if ! group_alive "$pgid"; then
            echo "this run's client process group $pgid is empty after KILL (${waited}s)"
            return 0
        fi
    done
    echo "WARNING: processes remain in this run's client process group $pgid after KILL" >&2
    if leader_running "$leader" "$start"; then
        echo "WARNING: this run's client supervisor $leader is one of them" >&2
    fi
    return 1
}

# Before the identity record exists, the only process this script can name is the job it started: a
# direct child that has not been reaped yet, so its pid cannot belong to anything else. TERM, ten
# seconds, then KILL. Used when the supervisor never writes its record, and by the exit trap in that
# short window.
stop_client_job() {
    local job="$1" waited state
    [[ -n "$job" ]] || return 0
    state="$(proc_stat_field "$job" 3)" || return 0
    [[ "$state" != Z && "$state" != X ]] || return 0
    echo "stopping the client job $job (no group identity was recorded)"
    kill -TERM "$job" 2>/dev/null || true
    for waited in {1..10}; do
        sleep 1
        state="$(proc_stat_field "$job" 3)" || {
            echo "client job $job exited after TERM (${waited}s)"
            return 0
        }
        if [[ "$state" == Z || "$state" == X ]]; then
            echo "client job $job exited after TERM (${waited}s)"
            return 0
        fi
    done
    echo "client job $job ignored TERM; sending KILL" >&2
    kill -KILL "$job" 2>/dev/null || true
}

script_pgid="$(proc_stat_field "$$" 5)" || {
    echo "error: could not read this script's process group from /proc/$$/stat" >&2
    exit 1
}

restore() {
    restore_code=$?
    if [[ "$client_active" == true ]]; then
        # An interrupt or an early exit still stops what this invocation started: the verified group
        # once its identity is recorded, otherwise only the un-reaped direct child.
        if [[ -n "$leader_pid" ]]; then
            stop_client_group "$leader_pid" "$leader_pgid" "$leader_start" || true
        else
            stop_client_job "$client_job" || true
        fi
    fi
    if [[ "$backup_ready" == true ]]; then
        if cp -p "$backup" "$mod" && cmp -s "$backup" "$mod"; then
            echo "restored $mod"
            rm -f "$backup"
        else
            echo "ERROR: could not restore $mod byte for byte; backup kept at $backup" >&2
            restore_code=1
        fi
    fi
    if [[ -n "$marker" ]]; then
        rm -f "$marker"
    fi
    if [[ -n "$leader_file" ]]; then
        rm -f "$leader_file"
    fi
    if [[ -n "$client_file" ]]; then
        rm -f "$client_file"
    fi
    if [[ -n "$client_status_file" ]]; then
        rm -f "$client_status_file"
    fi
    if [[ "$backup_ready" != true ]]; then
        rm -f "$backup"
    fi
    exit "$restore_code"
}
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cp -p "$mod" "$backup"
backup_ready=true
marker="$(mktemp "${TMPDIR:-/tmp}/quicraft-causal-login-run.XXXXXX")"

python3 - "$mod" "$entrypoint" <<'PY'
import json
import sys

path, entrypoint = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    document = json.load(handle)
document.setdefault("entrypoints", {})["fabric-client-gametest"] = [entrypoint]
with open(path, "w", encoding="utf-8") as handle:
    json.dump(document, handle, indent=2)
    handle.write("\n")
with open(path, encoding="utf-8") as handle:
    narrowed = json.load(handle)
listed = narrowed["entrypoints"]["fabric-client-gametest"]
if listed != [entrypoint]:
    raise SystemExit(f"entrypoint narrowing failed: {listed}")
PY

gradle=("$root/gradlew" -p "$root")
# The resource task keeps the daemon. The client must not use it: a daemon started by an earlier
# build forks the game JVM outside this script's session, where the recorded identity cannot reach
# it. --no-daemon keeps the Gradle build in the launcher process, a child of the session supervisor,
# so the GameTest JVM it forks inherits the owned process group.
client_gradle=("$root/gradlew" -p "$root" --no-daemon)
run=(env ALSOFT_DRIVERS=null)
if ((${#xvfb[@]} > 0)); then
    run+=("${xvfb[@]}")
fi
run+=("${client_gradle[@]}" :fabric-26x:runClientGameTest --console=plain)
resource_status=0
echo "== processGametestResources (forced, so the narrowed entrypoint list is used)"
if env ALSOFT_DRIVERS=null "${gradle[@]}" :fabric-26x:processGametestResources --rerun-tasks \
    --console=plain >"$log" 2>&1; then
    resource_status=0
else
    resource_status=$?
fi

failures=()
if ((resource_status != 0)); then
    failures+=("gradle failed while processing the gametest resources (status $resource_status)")
fi

client_status=0
client_timed_out=false
client_ran=false
if ((resource_status == 0)); then
    if python3 - "$processed" "$entrypoint" <<'PY'
import json
import sys

path, entrypoint = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as handle:
    document = json.load(handle)
listed = document["entrypoints"]["fabric-client-gametest"]
if listed != [entrypoint]:
    raise SystemExit(f"processed entrypoint list is still {listed}")
PY
    then
        echo "== runClientGameTest (CausalLoginGameTest only, ${client_timeout}s limit), log: $log"
        echo "client invocation (owned session, --no-daemon): ${run[*]}"
        echo "== client invocation: ${run[*]}" >>"$log"
        client_ran=true
        leader_file="$(mktemp "${TMPDIR:-/tmp}/quicraft-causal-login-leader.XXXXXX")"
        client_file="$(mktemp "${TMPDIR:-/tmp}/quicraft-causal-login-client.XXXXXX")"
        client_status_file="$(mktemp "${TMPDIR:-/tmp}/quicraft-causal-login-status.XXXXXX")"
        # The shell that setsid starts is the new session's leader and supervisor. It records its
        # own pid, process group and /proc starttime, starts the client in that session, records the
        # client process and the group it inherited, waits for the client, records its status, and
        # then holds the session open until this script stops it. The exec to sleep keeps the pid and
        # starttime, so the identity stays verifiable after the client or the game JVM has exited.
        setsid --wait bash -c '
            ident="$1"
            client_record="$2"
            status="$3"
            shift 3
            cmd=("$@")
            stat=""
            # A redirect on read runs in this shell; "$(</proc/self/stat)" would read a subshell.
            IFS= read -r -d "" stat < /proc/self/stat || true
            stat="${stat##*)}"
            set -- $stat
            printf "%s %s %s\n" "$$" "$3" "${20}" >"$ident"
            "${cmd[@]}" &
            client=$!
            client_stat=""
            IFS= read -r -d "" client_stat < "/proc/$client/stat" || true
            client_stat="${client_stat##*)}"
            set -- $client_stat
            printf "%s %s\n" "$client" "$3" >"$client_record"
            wait "$client"
            client_status=$?
            printf "%s\n" "$client_status" >"$status"
            exec sleep 2147483647
        ' causal-login-client-supervisor "$leader_file" "$client_file" "$client_status_file" "${run[@]}" >>"$log" 2>&1 &
        client_job=$!
        client_active=true
        for _ in {1..50}; do
            if [[ -s "$leader_file" ]] && read -r leader_pid leader_pgid leader_start <"$leader_file" \
                && [[ -n "$leader_pid" && -n "$leader_pgid" && -n "$leader_start" ]]; then
                break
            fi
            leader_pid=""
            leader_pgid=""
            leader_start=""
            sleep 0.1
        done
        if [[ -z "$leader_pid" ]]; then
            failures+=("the client supervisor recorded no process identity, so this run's process group cannot be verified")
            echo "error: no client process identity was recorded; stopping the job this script started" >&2
            stop_client_job "$client_job" || true
            wait "$client_job" 2>/dev/null || true
            client_active=false
            client_status=1
        else
            for _ in {1..50}; do
                if [[ -s "$client_file" ]] && read -r client_pid client_pgid <"$client_file" \
                    && [[ -n "$client_pid" && -n "$client_pgid" ]]; then
                    break
                fi
                client_pid=""
                client_pgid=""
                sleep 0.1
            done
            if [[ -z "$client_pid" ]]; then
                failures+=("the client supervisor recorded no client process, so it cannot be shown inside the owned session")
                echo "error: no client process was recorded; stopping this run's process group" >&2
                stop_client_group "$leader_pid" "$leader_pgid" "$leader_start" || true
                if ! leader_running "$leader_pid" "$leader_start"; then
                    wait "$client_job" 2>/dev/null || true
                fi
                client_active=false
                client_status=1
            elif [[ "$client_pgid" != "$leader_pgid" ]]; then
                failures+=("the client process $client_pid runs in group $client_pgid, not the owned session group $leader_pgid")
                echo "error: the client process escaped the owned session; stopping this run's process group" >&2
                stop_client_group "$leader_pid" "$leader_pgid" "$leader_start" || true
                if ! leader_running "$leader_pid" "$leader_start"; then
                    wait "$client_job" 2>/dev/null || true
                fi
                client_active=false
                client_status=1
            else
                echo "client process $client_pid is in this run's owned session group $leader_pgid (supervisor $leader_pid, --no-daemon); the Gradle build JVM and the GameTest JVM it forks are descended from it in that group"
                echo "== ownedClient pid=$client_pid pgid=$client_pgid supervisor=$leader_pid noDaemon=true" >>"$log"
                deadline=$((SECONDS + client_timeout))
                client_status=""
                while [[ -z "$client_status" ]]; do
                    if [[ -s "$client_status_file" ]] \
                        && read -r recorded_status <"$client_status_file" \
                        && [[ "$recorded_status" =~ ^[0-9]+$ ]]; then
                        client_status="$recorded_status"
                        break
                    fi
                    if ! leader_running "$leader_pid" "$leader_start"; then
                        failures+=("the client supervisor $leader_pid exited before it recorded a client status")
                        client_status=1
                        break
                    fi
                    if ((SECONDS >= deadline)); then
                        client_timed_out=true
                        client_status=124
                        break
                    fi
                    sleep 1
                done
                stop_client_group "$leader_pid" "$leader_pgid" "$leader_start" || true
                if ! leader_running "$leader_pid" "$leader_start"; then
                    wait "$client_job" 2>/dev/null || true
                fi
                client_active=false
            fi
        fi
    else
        failures+=("the processed resource is not narrowed: the source edit did not reach the run")
    fi
fi
if [[ ! -f "$log" ]] || ! grep -qF 'CAUSAL-LOGIN' "$log"; then
    failures+=("the log has no CAUSAL-LOGIN line: the probe did not run (log: $log)")
fi
if [[ ! -f "$summary" ]]; then
    failures+=("the probe wrote no summary at $summary")
else
    first="$(head -n 1 "$summary")"
    if [[ "$first" != *"status=COMPLETED"* ]]; then
        failures+=("the summary is not a completed run: $first")
    fi
    if [[ "$first" != *"started="* || "$first" == *"started=none"* ]]; then
        failures+=("the summary has no run start stamp: $first")
    fi
    if [[ ! "$summary" -nt "$marker" ]]; then
        failures+=("the summary is stale, older than this run's marker: $first")
    fi
    if python3 - "$summary" "$report_dir" "$marker" <<'PY'
import glob
import json
import os
import sys

summary, report_dir, marker = sys.argv[1:]
with open(summary, encoding="utf-8") as handle:
    first = handle.readline().strip()
if "status=COMPLETED" not in first or "started=" not in first:
    raise SystemExit(f"invalid summary header: {first}")
started = first.split("started=", 1)[1].split()[0]
paths = glob.glob(os.path.join(report_dir, "causal-login-*.json"))
paths = [p for p in paths if os.path.getmtime(p) > os.path.getmtime(marker)]
if not paths:
    raise SystemExit("no fresh causal-login JSON report")
path = max(paths, key=os.path.getmtime)
with open(path, encoding="utf-8") as handle:
    report = json.load(handle)
if report.get("status") != "COMPLETED" or report.get("started") != started:
    raise SystemExit(f"JSON status/start mismatch: {path}")
expected = [None, "causal login spawn", "causal login ready", "causal place new player"]
rows = report.get("rows")
if not isinstance(rows, list) or len(rows) != 4:
    raise SystemExit(f"expected four matrix rows, got {rows!r}")
sequence = ["login_spawn:HEAD", "login_ready:HEAD", "login:HEAD", "login:RETURN", "login_ready:RETURN", "login_spawn:RETURN"]
for row, fault in zip(rows, expected):
    if row.get("status") != "PASS" or row.get("fault") != fault or row.get("errors"):
        raise SystemExit(f"failed matrix row: {row!r}")
    if row.get("events", []) == [] or [event.get("key") for event in row["events"]] != sequence:
        raise SystemExit(f"wrong event sequence in row: {row!r}")
PY
    then
        :
    else
        failures+=("fresh JSON report does not prove the complete four-case matrix")
    fi
fi

# A nonzero client status is the known "Stopping!" hang, or a real failure. Only complete, fresh
# evidence distinguishes them: with that evidence the capture is accepted and labelled
# shutdownHang=true; without it the client status is one more reason to fail closed.
shutdown_hang=false
if [[ "$client_ran" == true ]] && ((client_status != 0)); then
    if ((${#failures[@]} == 0)); then
        shutdown_hang=true
    else
        detail="runClientGameTest exited with status $client_status"
        if [[ "$client_timed_out" == true ]]; then
            detail+=" after the ${client_timeout}s internal timeout"
        fi
        failures+=("$detail")
    fi
fi

if ((${#failures[@]} > 0)); then
    {
        echo "FAILED: the causal-login probe did not run to a verifiable completion."
        for failure in "${failures[@]}"; do
            echo "  - $failure"
        done
        echo "  log: $log"
        if [[ -f "$log" ]]; then
            echo "  last log lines:"
            tail -n 20 "$log"
        fi
    } >&2
    exit 1
fi

if [[ "$shutdown_hang" == true ]]; then
    warning="WARNING: shutdownHang=true clientStatus=$client_status timedOut=$client_timed_out internalTimeout=${client_timeout}s; the client did not exit cleanly, but CAUSAL-LOGIN and a fresh COMPLETED summary were captured"
    echo "$warning"
    echo "$warning" >>"$log"
fi
echo "OK: CAUSAL-LOGIN completed"
head -n 1 "$summary"
