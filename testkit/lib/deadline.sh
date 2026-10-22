# SPDX-License-Identifier: GPL-3.0-or-later
# Every long-running command gets a new process group. Kill and reap it before resource restoration.
command -v setsid >/dev/null && command -v timeout >/dev/null || {
    echo 'setsid and GNU timeout are required' >&2; exit 2;
}
active_pgid=""
stop_child() {
    [[ -n "$active_pgid" ]] || return 0
    kill -TERM -- "-$active_pgid" 2>/dev/null || true
    for ((grace = 0; grace < 30; grace++)); do
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
    setsid timeout --kill-after=30 "$budget" "$@" &
    active_pgid=$!
    wait "$active_pgid" || status=$?
    # A command may have left descendants behind even after a successful parent exit.
    stop_child
    return "$status"
}
