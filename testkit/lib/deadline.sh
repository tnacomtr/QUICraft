# SPDX-License-Identifier: GPL-3.0-or-later
# Every long-running command gets a new process group. Kill and reap it before resource restoration.
command -v setsid >/dev/null && command -v timeout >/dev/null || {
    echo 'setsid and GNU timeout are required' >&2; exit 2;
}
active_pgid=""
active_grace=20
# Nested runners must finish their inner group kill before the outer supervisor kills them.
# Refuse even an unused invalid override before starting any child.
DEADLINE_INNER_GRACE=${DEADLINE_INNER_GRACE:-20}
DEADLINE_OUTER_GRACE=${DEADLINE_OUTER_GRACE:-60}
[[ "$DEADLINE_INNER_GRACE" =~ ^[1-9][0-9]{0,3}$ && "$DEADLINE_OUTER_GRACE" =~ ^[1-9][0-9]{0,3}$ ]] \
    && ((DEADLINE_OUTER_GRACE > DEADLINE_INNER_GRACE)) || {
    echo 'outer kill grace must be strictly greater than inner kill grace' >&2; exit 2;
}
# Client startup/join/chunk quiet/shutdown allowance, in addition to the requested play window.
# Includes the 90s join and 60s chunk timeouts plus 150s JVM startup and shutdown slack.
CLIENT_RUN_ALLOWANCE=300
# Build (3 x 1200s), readiness, netem and resource cleanup, excluding the client window.
MATRIX_RUN_ALLOWANCE=4800
client_run_budget() { echo "$((10#$1 + CLIENT_RUN_ALLOWANCE))"; }
matrix_run_budget() { echo "$(($(client_run_budget "$1") + MATRIX_RUN_ALLOWANCE))"; }
stop_child() {
    [[ -n "$active_pgid" ]] || return 0
    kill -TERM -- "-$active_pgid" 2>/dev/null || true
    for ((grace = 0; grace < active_grace; grace++)); do
        kill -0 -- "-$active_pgid" 2>/dev/null || break
        sleep 1
    done
    kill -KILL -- "-$active_pgid" 2>/dev/null || true
    wait "$active_pgid" 2>/dev/null || true
    active_pgid=""
}
bounded() {
    local budget="$1" status=0
    shift
    active_grace=${deadline_grace:-$DEADLINE_INNER_GRACE}
    setsid timeout --kill-after="$active_grace" "$budget" "$@" &
    active_pgid=$!
    wait "$active_pgid" || status=$?
    # A command may have left descendants behind even after a successful parent exit.
    stop_child
    return "$status"
}
bounded_outer() {
    local deadline_grace=$DEADLINE_OUTER_GRACE
    bounded "$@"
}
