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
    echo "netem '$profile' on $dev (both directions): $args"
fi

uid="${HOST_UID:-0}"
gid="${HOST_GID:-0}"
if [ "$uid" != 0 ]; then
    export HOME=/tmp
    exec setpriv --reuid="$uid" --regid="$gid" --clear-groups --inh-caps=-all \
        /opt/testkit/bin/quicraft-testkit "$@"
fi
exec /opt/testkit/bin/quicraft-testkit "$@"
