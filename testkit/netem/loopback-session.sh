#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs one command inside a fresh user+network namespace whose loopback carries a netem profile
# from testkit/netem/profiles.sh.
#
#   testkit/netem/loopback-session.sh --profile delayloss -- \
#       ./gradlew :fabric-26x:runClientGameTest -Pquicraft.desync=true ...
#
# Why this and not netem-attach.sh: the desync sessions run the dedicated server inside the client
# JVM, so the whole game path is loopback. netem-attach.sh shapes a Compose project's fabric
# service on eth0; server-netem.sh shapes the default-route device and an IFB by game port. Neither
# ever sees loopback traffic, and shaping the host's lo would need root and would affect every
# loopback user on the machine. A private network namespace gives the run its own lo instead.
#
# Netem on lo egress shapes each packet once per direction (client to server and server to client),
# which is the same two-sided shaping netem-attach.sh applies to a container's eth0. The profiles'
# 75 ms delay therefore still means about 150 ms RTT.
#
# Requires Linux with unprivileged user namespaces and ip/tc from iproute2. It refuses to run the
# command unshaped: a "clean-looking" pass without the profile would be false evidence.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=profiles.sh
. "$here/profiles.sh"

usage() {
    {
        echo "usage: loopback-session.sh --profile PROFILE -- COMMAND [ARG...]"
        echo "  PROFILE: clean | loss | delay | delayloss | reorder | jitter | slow"
    } >&2
    exit "${1:-2}"
}

die() {
    echo "error: $*" >&2
    exit 1
}

profile="clean"
while (($# > 0)); do
    case "$1" in
        --profile)
            (($# >= 2)) || usage
            profile="$2"
            shift 2
            ;;
        --)
            shift
            break
            ;;
        -h | --help)
            usage 0
            ;;
        *)
            die "unknown argument '$1' (the command goes after --)"
            ;;
    esac
done
netem_args "$profile" >/dev/null || die "unknown netem profile '$profile'"
(($# > 0)) || usage

if [[ "${QUICRAFT_LOOPBACK_NETNS:-0}" != 1 ]]; then
    command -v unshare >/dev/null 2>&1 \
        || die "unshare is required (util-linux); this helper will not run a session without a private netns"
    command -v ip >/dev/null 2>&1 || die "ip from iproute2 is required"
    command -v tc >/dev/null 2>&1 || die "tc from iproute2 is required"
    if ! unshare --user --map-root-user --net -- true 2>/dev/null; then
        die "cannot create a user+network namespace: 'unshare --user --map-root-user --net' failed." \
            "Unprivileged user namespaces are probably disabled (kernel.unprivileged_userns_clone=0 or an" \
            "AppArmor/seccomp policy). Enable them or run this on a host that allows it; the session" \
            "is not started unshaped."
    fi
    export QUICRAFT_LOOPBACK_NETNS=1
    exec unshare --user --map-root-user --net -- "$0" --profile "$profile" -- "$@"
fi

# Inside the private namespace: root is the mapped user, so ip/tc are allowed here and only here.
ip link set dev lo up || die "could not bring loopback up in the private netns"
args="$(netem_args "$profile")"
if [[ -n "$args" ]]; then
    # shellcheck disable=SC2086 # the profile is a deliberate word list for tc
    tc qdisc add dev lo root netem $args \
        || die "tc could not attach '$args' to lo; the session is not started unshaped"
    if ! tc qdisc show dev lo | grep -q netem; then
        die "tc reported success but lo carries no netem qdisc; refusing unshaped evidence"
    fi
    echo "loopback netem: $profile ($args)" >&2
else
    echo "loopback netem: clean (no qdisc)" >&2
fi

exec "$@"
