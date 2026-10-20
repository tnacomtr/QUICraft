#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Launcher for testkit/netem/server-netem.sh against a fresh, uniquely named real-Fabric
# Compose project. This implements the MANDATORY FUTURE-LAUNCHER GATE documented at the top
# of testkit/netem/server-netem.sh; that script cannot prove its own namespace ownership.
#
# Usage:
#   testkit/netem-attach.sh apply  --project quicraft-arm-<id> --profile <p> [--port 25565]
#   testkit/netem-attach.sh status --project quicraft-arm-<id> [--profile <p>] [--port 25565]
#
# Profiles: clean loss delay delayloss reorder jitter.
# status defaults to --profile delay because that is the layout that carries the egress and
# ingress filters (the profile name only decides whether an IFB is expected; the counters
# are read from the namespace). Pass --profile clean after a clean attach.
#
# Safety model:
#   * Only Compose projects matching ^quicraft-arm-[a-z0-9-]+$ are accepted.
#   * Exactly one RUNNING container labelled com.docker.compose.project=<project> and
#     com.docker.compose.service=fabric must exist, with a full 64-hex id.
#   * The target must not use host or container networking.
#   * A non-privileged probe container is created WITHOUT --rm, inspected to prove
#     HostConfig.NetworkMode == container:<target id>, and its printed IPv4 addresses must
#     equal the target's inspected IPv4 addresses. Only then is the NET_ADMIN helper run.
#   * The only containers removed are the probe containers this script created, addressed by
#     the quicraft-netem-<random> name it generated. A failed create is never deleted because
#     ownership was not established.
#   * No --network host, no --privileged, no sudo, no host tc/ip and no host firewall/route
#     command. Everything privileged runs inside the joined helper containers.
set -eu
set -f

prog=netem-attach.sh
image=quicraft-testkit-bench
helper_script=/usr/local/bin/server-netem.sh

usage() {
    echo "usage: $prog apply --project <name> --profile <profile> [--port <port>]" >&2
    echo "       $prog status --project <name> [--profile <profile>] [--port <port>]" >&2
    echo "profile: clean loss delay delayloss reorder jitter" >&2
    echo "port: internal game port, default 25565" >&2
    exit 2
}

refuse() {
    echo "$prog: ERROR: $*" >&2
    exit 2
}

[ "$#" -ge 1 ] || usage
mode=$1
shift
case "$mode" in
    apply | status) ;;
    *) usage ;;
esac

project=
profile=
port=25565
while [ "$#" -gt 0 ]; do
    case "$1" in
        --project | --profile | --port)
            [ "$#" -ge 2 ] || usage
            opt=$1
            val=$2
            shift 2
            ;;
        --project=* | --profile=* | --port=*)
            opt=${1%%=*}
            val=${1#*=}
            shift
            ;;
        *) usage ;;
    esac
    case "$opt" in
        --project) project=$val ;;
        --profile) profile=$val ;;
        --port) port=$val ;;
    esac
done

[ -n "$project" ] || usage
case "$mode" in
    apply) [ -n "$profile" ] || usage ;;
    status) [ -n "$profile" ] || profile=delay ;;
esac

case "$profile" in
    clean | loss | delay | delayloss | reorder | jitter) ;;
    *) refuse "unknown profile '$profile' (allowed: clean loss delay delayloss reorder jitter)" ;;
esac

case "$port" in
    '' | 0* | *[!0-9]*) usage ;;
esac
[ "${#port}" -le 5 ] || usage
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    usage
fi

# Runner-owned project names only: this script must never target someone else's containers.
case "$project" in
    quicraft-arm-*) ;;
    *) refuse "project must match ^quicraft-arm-[a-z0-9-]+$ (got '$project')" ;;
esac
suffix=${project#quicraft-arm-}
case "$suffix" in
    '' | *[!a-z0-9-]*) refuse "project must match ^quicraft-arm-[a-z0-9-]+$ (got '$project')" ;;
esac

command -v docker >/dev/null 2>&1 || refuse "docker is not installed"

here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) \
    || refuse "cannot resolve the directory of this script"
netem_src=$here/netem/server-netem.sh
[ -f "$netem_src" ] || refuse "helper script $netem_src is missing"
case "$netem_src" in
    *:*) refuse "helper script path contains ':' and cannot be bind-mounted: $netem_src" ;;
esac

# Resolve the fabric container of exactly this Compose project.
ids=$(docker ps --quiet --no-trunc \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=fabric" \
    --filter status=running) || refuse "docker ps failed for project '$project'"
# shellcheck disable=SC2086 # whitespace/newline separated container ids
set -- $ids
[ "$#" -ge 1 ] || refuse "no running fabric container in project '$project'"
[ "$#" -eq 1 ] || refuse "expected exactly one running fabric container in project '$project', found $#"
target=$1
case "$target" in
    '' | *[!0-9a-f]*) refuse "docker ps returned a non-hex container id: '$target'" ;;
esac
[ "${#target}" -eq 64 ] || refuse "docker ps did not return a full 64-hex container id: '$target'"

target_format='{{.Id}}|{{.State.Running}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.HostConfig.NetworkMode}}|{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}'
meta=$(docker inspect --type container --format "$target_format" "$target") \
    || refuse "docker inspect failed for target $target"
old_ifs=$IFS
IFS='|'
# shellcheck disable=SC2086 # deliberate IFS='|' split of the fixed inspect template
set -- $meta
IFS=$old_ifs
[ "$#" -eq 6 ] || refuse "unexpected docker inspect output for target $target"
seen_id=$1
seen_running=$2
seen_project=$3
seen_service=$4
target_mode=$5
target_addrs=$6

[ "$seen_id" = "$target" ] || refuse "target id mismatch: asked $target, inspect returned $seen_id"
[ "$seen_running" = true ] || refuse "target $target is not running"
[ "$seen_project" = "$project" ] || refuse "target $target project label is '$seen_project', expected '$project'"
[ "$seen_service" = fabric ] || refuse "target $target service label is '$seen_service', expected 'fabric'"
case "$target_mode" in
    host | container:* | '')
        refuse "target $target network mode '$target_mode' is not a private bridge network" ;;
esac

target_ips=
for addr in $target_addrs; do
    case "$addr" in
        *[!0-9.]*) refuse "target $target has a non-IPv4 address '$addr'" ;;
    esac
    target_ips="$target_ips $addr"
done
[ -n "$target_ips" ] || refuse "target $target has no IPv4 address to verify"

# Probe name: random and generated here, so only this script's helpers are ever removed.
rand=$(od -An -N8 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n') || rand=
case "$rand" in
    '' | *[!0-9a-f]*) refuse "cannot generate a unique helper container name" ;;
esac
probe=quicraft-netem-$rand
probe_owned=0

cleanup_probe() {
    [ "$probe_owned" -eq 1 ] || return 0
    docker rm --force "$probe" >/dev/null 2>&1 || true
    probe_owned=0
}
trap cleanup_probe EXIT

# Non-privileged probe, deliberately not --rm: it must be inspected after it exists.
# A failed create is not removed, because ownership cannot be proven (the name may belong
# to a pre-existing container).
docker create --name "$probe" --network "container:$target" --entrypoint ip \
    "$image" -4 -o addr show >/dev/null \
    || refuse "cannot create probe container '$probe'"
probe_owned=1

probe_addrs_raw=$(docker start --attach "$probe") \
    || refuse "probe container '$probe' failed"

probe_format='{{.Id}}|{{.HostConfig.NetworkMode}}|{{.Name}}|{{.Config.Image}}'
probe_meta=$(docker inspect --type container --format "$probe_format" "$probe") \
    || refuse "cannot inspect probe container '$probe'"
IFS='|'
# shellcheck disable=SC2086 # deliberate IFS='|' split of the fixed inspect template
set -- $probe_meta
IFS=$old_ifs
[ "$#" -eq 4 ] || refuse "unexpected docker inspect output for probe '$probe'"
probe_netmode=$2
probe_name_field=$3
probe_image=$4

[ "$probe_netmode" = "container:$target" ] \
    || refuse "probe container network mode is '$probe_netmode', expected container:$target"
[ "$probe_name_field" = "/$probe" ] || refuse "probe container name is '$probe_name_field', expected /$probe"
[ "$probe_image" = "$image" ] || refuse "probe container image is '$probe_image', expected $image"

probe_ips=
while IFS= read -r line; do
    case "$line" in
        *" inet "*) ;;
        *) continue ;;
    esac
    rest=${line#* inet }
    addr=${rest%% *}
    addr=${addr%%/*}
    case "$addr" in
        '' | 127.*) continue ;;
    esac
    case "$addr" in
        *[!0-9.]*) refuse "probe container printed a non-IPv4 address '$addr'" ;;
    esac
    probe_ips="$probe_ips $addr"
done <<EOF
$probe_addrs_raw
EOF
[ -n "$probe_ips" ] || refuse "probe container printed no non-loopback IPv4 address"

# shellcheck disable=SC2086 # whitespace-separated address lists built above
set -- $probe_ips
probe_count=$#
# shellcheck disable=SC2086
set -- $target_ips
target_count=$#
[ "$probe_count" -eq "$target_count" ] \
    || refuse "IPv4 mismatch: target [$target_ips ] probe [$probe_ips ]"
for addr in $probe_ips; do
    case " $target_ips " in
        *" $addr "*) ;;
        *) refuse "IPv4 mismatch: probe sees $addr, target has [$target_ips ]" ;;
    esac
done
for addr in $target_ips; do
    case " $probe_ips " in
        *" $addr "*) ;;
        *) refuse "IPv4 mismatch: target has $addr, probe sees [$probe_ips ]" ;;
    esac
done

if ! rm_out=$(docker rm --force "$probe" 2>&1); then
    refuse "could not remove the helper container '$probe': $rm_out"
fi
probe_owned=0

echo "$prog: project=$project target=$target profile=$profile port=$port mode=$mode"

rc=0
docker run --rm --network "container:$target" --cap-add NET_ADMIN \
    -e QUICRAFT_OWNED_NETNS=1 \
    -v "$netem_src:$helper_script:ro" \
    --entrypoint /bin/sh "$image" \
    "$helper_script" "$mode" "$profile" "$port" || rc=$?
if [ "$rc" -ne 0 ]; then
    echo "$prog: ERROR: server-netem.sh exited $rc" >&2
fi
exit "$rc"
