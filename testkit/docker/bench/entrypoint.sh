#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Applies the NETEM_PROFILE to this container's link, then runs the testkit command as
# HOST_UID:HOST_GID so result files on the bind mount belong to the caller.
set -eu
. /opt/testkit/netem-profiles.sh

profile="${NETEM_PROFILE:-clean}"
if ! args="$(netem_args "$profile")"; then
    echo "unknown NETEM_PROFILE '$profile'" >&2
    exit 2
fi
if [ -n "$args" ]; then
    dev="$(ip route show default | awk '{print $5; exit}')"
    # shellcheck disable=SC2086
    tc qdisc add dev "$dev" root netem $args limit 10000
    ip link add ifb0 type ifb
    ip link set ifb0 up
    tc qdisc add dev "$dev" handle ffff: ingress
    tc filter add dev "$dev" parent ffff: protocol all u32 match u32 0 0 action mirred egress redirect dev ifb0
    # shellcheck disable=SC2086
    tc qdisc add dev ifb0 root netem $args limit 10000
    # netem drops whole packets, and with segmentation offload a TCP packet here can be 64 KB
    # of segments: TCP would see a fraction of the configured loss. One segment per packet, as
    # on a wire. (run-baseline.sh does the same on the server's side.)
    ip link set dev "$dev" gso_max_segs 1
    echo "netem '$profile' on $dev (both directions): $args"
fi

uid="${HOST_UID:-0}"
gid="${HOST_GID:-0}"
status=0
if [ "$uid" != 0 ]; then
    export HOME=/tmp
    setpriv --reuid="$uid" --regid="$gid" --clear-groups --inh-caps=-all \
        /opt/testkit/bin/quicraft-testkit "$@" || status=$?
else
    /opt/testkit/bin/quicraft-testkit "$@" || status=$?
fi
if [ -n "$args" ]; then
    # What netem actually handled: packets per direction, drops, bytes (docs/benchmarks.md).
    echo "netem stats, client to server ($dev):"
    tc -s qdisc show dev "$dev" | sed -n '/netem/,/backlog/p'
    echo "netem stats, server to client (ifb0):"
    tc -s qdisc show dev ifb0 | sed -n '/netem/,/backlog/p'
fi
exit "$status"
