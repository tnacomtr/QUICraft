#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Applies one existing testkit netem profile to a Fabric server's own network namespace, or
# prints its state. Only IPv4 TCP/UDP traffic using the internal game PORT is impaired: the
# server's egress packets with source port PORT and ingress packets with destination port PORT.
# IPv6 and everything else, including the mock session server's authentication traffic, stays
# in the clean band in both directions.
#
# MANDATORY FUTURE-LAUNCHER GATE (this script cannot prove it from inside):
#   1. The helper MUST run with --network container:<fresh fabric container id> and be granted
#      NET_ADMIN only after that join. Reject host networking and bridge networking first.
#   2. Before granting NET_ADMIN, the launcher MUST inspect the target and verify ALL of:
#      exact fresh Compose project labels (com.docker.compose.project=<unique expected value>,
#      com.docker.compose.service=fabric), HostConfig.NetworkMode=container:<that container id>,
#      and the helper's actual IPv4 address equal to the target's in the joined namespace.
#      Reject reused/stale projects.
#   3. /.dockerenv and QUICRAFT_OWNED_NETNS=1 checked below are ACCIDENT GUARDS, not ownership
#      proof. They do not stop a malicious root or a helper attached to the host network.
#   4. Before any number is treated as a measurement, independently prove that the kernel has
#      sch_netem and ifb, that the IFB is created in this namespace, and that real IPv4 TCP and
#      UDP game flows hit the filters in both directions. Rendered state alone proves neither
#      packet shaping nor throughput.
#
# No host changes: every command targets the namespace the launcher joined. Stale state is
# refused, never deleted. This script has no sudo/module/route/firewall command and no tc or ip
# delete/remove/flush command.
set -eu

profiles_file=/opt/testkit/netem-profiles.sh
ifb=qcbench0

usage() {
    echo "usage: server-netem.sh apply|status PROFILE PORT" >&2
    echo "PROFILE: clean loss delay delayloss reorder jitter" >&2
    echo "PORT: internal game port, integer 1..65535" >&2
    exit 2
}

refuse() {
    echo "server-netem.sh: ERROR: $*" >&2
    exit 2
}

unusable() {
    echo "server-netem.sh: ERROR after mutation: $*" >&2
    echo "server-netem.sh: WARNING: namespace ${netns:-unknown} is partially configured and unusable." >&2
    echo "server-netem.sh: WARNING: tear down and recreate the helper/target project; no automatic cleanup is performed." >&2
    exit 1
}

[ "$#" -eq 3 ] || usage
mode=$1
profile=$2
port=$3
case "$mode" in
    apply | status) ;;
    *) usage ;;
esac
case "$port" in
    '' | 0* | *[!0-9]*) usage ;;
esac
if [ "${#port}" -gt 5 ]; then
    usage
fi
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    usage
fi

# Accident guards, not proof of a private, fresh namespace. See the mandatory launcher gate.
[ -f /.dockerenv ] || refuse "refusing to run outside the testkit helper container (/.dockerenv is missing)"
[ "${QUICRAFT_OWNED_NETNS:-}" = 1 ] || refuse "QUICRAFT_OWNED_NETNS=1 is required; the launcher must verify fresh namespace ownership first"
command -v ip >/dev/null 2>&1 || refuse "ip (iproute2) is not installed"
command -v tc >/dev/null 2>&1 || refuse "tc (iproute2) is not installed"

# Trusted installed copy from the bench image; never take a profile file path from the caller.
[ -f "$profiles_file" ] || refuse "trusted profile file $profiles_file is missing"
[ ! -L "$profiles_file" ] || refuse "trusted profile file $profiles_file must not be a symlink"
[ -r "$profiles_file" ] || refuse "trusted profile file $profiles_file is not readable"
# shellcheck source=/dev/null
. "$profiles_file"
args="$(netem_args "$profile")" || refuse "unknown PROFILE '$profile' (allowed: clean loss delay delayloss reorder jitter)"

netns="$(readlink /proc/self/ns/net)" || refuse "cannot read the current network namespace identity"
route="$(ip -4 route show default)" || refuse "cannot read IPv4 default routes"
[ -n "$route" ] || refuse "no IPv4 default route in this namespace"
newline='
'
case "$route" in
    *"$newline"*) refuse "more than one IPv4 default route; refusing to guess the device" ;;
esac
# shellcheck disable=SC2086 # whitespace-separated output from ip -4 route show
set -- $route
dev=
while [ "$#" -gt 0 ]; do
    if [ "$1" = dev ]; then
        shift
        [ "$#" -gt 0 ] || refuse "IPv4 default route has no device"
        [ -z "$dev" ] || refuse "IPv4 default route names more than one device"
        dev=$1
    fi
    shift
done
[ -n "$dev" ] || refuse "IPv4 default route has no device"

print_context() {
    echo "netns: $netns"
    echo "mode: $mode"
    echo "profile: $profile"
    echo "netem_args: ${args:-<clean>}"
    echo "device: $dev"
    echo "internal_port: $port"
}

# Freshness is checked before any mutation, for clean and impaired profiles alike. A root
# qdisc other than noqueue, any ingress/clsact qdisc, or an existing qcbench0 means this
# namespace is not fresh; refuse it and leave it untouched.
require_fresh() {
    all_qdiscs="$(tc qdisc show dev "$dev" 2>/dev/null)" || refuse "cannot inspect qdiscs on $dev"
    case "$all_qdiscs" in
        *"qdisc ingress"* | *"qdisc clsact"*)
            refuse "existing ingress/clsact qdisc on $dev; refusing stale state" ;;
    esac
    # shellcheck disable=SC2086 # first two tokens of the fixed tc output
    set -- $all_qdiscs
    [ "$#" -ge 2 ] || refuse "cannot identify the root qdisc on $dev"
    if [ "$1" != qdisc ] || [ "$2" != noqueue ]; then
        refuse "root qdisc on $dev is not fresh noqueue"
    fi
    if ip link show dev "$ifb" >/dev/null 2>&1; then
        refuse "existing $ifb interface; refusing stale state"
    fi
}

if [ "$mode" = apply ]; then
    require_fresh
    print_context
    if [ -z "$args" ]; then
        ip -d link show dev "$dev" || refuse "cannot print link state for $dev"
        tc -s qdisc show dev "$dev" || refuse "cannot print qdisc state for $dev"
        echo "clean: namespace left unshaped; no qdisc, filter, IFB or offload change applied"
        exit 0
    fi

    # First mutation. Any later failure prints the teardown-required warning and exits nonzero.
    ip link set dev "$dev" gso_max_segs 1 \
        || unusable "ip link set dev $dev gso_max_segs 1 failed"
    tc qdisc add dev "$dev" root handle 1: prio bands 3 \
        priomap 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 \
        || unusable "could not add the root prio qdisc on $dev"
    # shellcheck disable=SC2086 # args comes only from the fixed trusted allowlist function
    tc qdisc add dev "$dev" parent 1:3 handle 30: netem $args limit 10000 \
        || unusable "could not attach netem to $dev band 3"
    tc filter add dev "$dev" parent 1: protocol ip prio 1 u32 \
        match ip protocol 6 0xff match ip sport "$port" 0xffff flowid 1:3 \
        || unusable "could not add the IPv4 TCP source-port filter on $dev"
    tc filter add dev "$dev" parent 1: protocol ip prio 1 u32 \
        match ip protocol 17 0xff match ip sport "$port" 0xffff flowid 1:3 \
        || unusable "could not add the IPv4 UDP source-port filter on $dev"
    ip link add "$ifb" type ifb \
        || unusable "could not create $ifb (is the ifb module available?)"
    ip link set dev "$ifb" up \
        || unusable "could not bring $ifb up"
    # shellcheck disable=SC2086 # args comes only from the fixed trusted allowlist function
    tc qdisc add dev "$ifb" root netem $args limit 10000 \
        || unusable "could not add netem to $ifb"
    tc qdisc add dev "$dev" handle ffff: ingress \
        || unusable "could not add the ingress qdisc on $dev"
    tc filter add dev "$dev" parent ffff: protocol ip prio 1 u32 \
        match ip protocol 6 0xff match ip dport "$port" 0xffff \
        action mirred egress redirect dev "$ifb" \
        || unusable "could not add the IPv4 TCP destination-port ingress filter on $dev"
    tc filter add dev "$dev" parent ffff: protocol ip prio 1 u32 \
        match ip protocol 17 0xff match ip dport "$port" 0xffff \
        action mirred egress redirect dev "$ifb" \
        || unusable "could not add the IPv4 UDP destination-port ingress filter on $dev"

    # Raw configuration state precedes the success line, so a failed read never follows a
    # success claim. These counters are not a throughput or loss result.
    tc -s qdisc show dev "$dev" || unusable "could not print qdisc state for $dev after configuration"
    tc -s qdisc show dev "$ifb" || unusable "could not print qdisc state for $ifb after configuration"
    echo "configured '$profile' ($args) for IPv4 TCP/UDP port $port on $dev egress and $ifb ingress"
    exit 0
fi

print_context
ip -d link || refuse "cannot print link state"
echo "== tc qdisc, $dev"
tc -s qdisc show dev "$dev" || refuse "cannot print qdisc state for $dev"
if [ -n "$args" ]; then
    if ! ip link show dev "$ifb" >/dev/null 2>&1; then
        echo "server-netem.sh: ERROR: impaired profile '$profile' but $ifb is missing" >&2
        exit 1
    fi
    echo "== tc qdisc, $ifb"
    tc -s qdisc show dev "$ifb" || refuse "cannot print qdisc state for $ifb"
    echo "== egress filters, $dev parent 1:"
    tc -s filter show dev "$dev" parent 1: || refuse "cannot print egress filters for $dev"
    echo "== ingress filters, $dev parent ffff:"
    tc -s filter show dev "$dev" parent ffff: || refuse "cannot print ingress filters for $dev"
else
    echo "clean: no netem qdisc, IFB or filters are expected; this profile shapes nothing"
fi
echo "raw tc counters above are state, not an inferred throughput or loss measurement"
exit 0
