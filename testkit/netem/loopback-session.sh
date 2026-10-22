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

args="$(netem_args "$profile")"
command -v unshare >/dev/null || die "unshare is required"
command -v ip >/dev/null || die "ip is required"
command -v tc >/dev/null || die "tc is required"
# No re-entry flag, environment variable or public inner mode can skip namespace creation.
exec unshare --user --map-root-user --net -- bash -euo pipefail -c '
die() { echo "error: $*" >&2; exit 1; }
profile="$1"; args="$2"; shift 2
[[ "$(ip -o link show | wc -l)" -eq 1 ]] || die "namespace must contain only lo"
before="$(ip -o link show dev lo)"
[[ "$before" != *",UP"* && "$before" != *"<UP"* ]] || die "lo was already UP"
ip link set dev lo up || die "could not bring private lo up"
after="$(ip -o link show dev lo)"
[[ "$after" == *",UP"* || "$after" == *"<UP"* ]] || die "lo did not transition DOWN to UP"
if [[ -n "$args" ]]; then
    # shellcheck disable=SC2086 # the profile is a deliberate word list for tc
    tc qdisc add dev lo root netem $args \
        || die "tc could not attach netem to lo; the session is not started unshaped"
    if ! tc qdisc show dev lo | grep -q netem; then
        die "tc reported success but lo carries no netem qdisc; refusing unshaped evidence"
    fi
    echo "loopback netem: $profile ($args)" >&2
else
    echo "loopback netem: clean (no qdisc)" >&2
fi

exec "$@"
' loopback-session "$profile" "$args" "$@"
