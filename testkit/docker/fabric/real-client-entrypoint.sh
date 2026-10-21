#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Testkit-only wrapper for the Fabric dedicated server image. It prepares a fresh QUICraft
# server config and then EXECs the image's normal entrypoint, installed by
# docker/fabric/Dockerfile at /usr/local/bin/fabric-entrypoint.sh.
#
# Required, with no defaults in this script: FABRIC_ONLINE_MODE=true|false and
# REAL_FABRIC_MULTISTREAM=true|false. Unset, empty or invalid values are refused. This fixture
# never silently falls back to TCP, to the base entrypoint's defaults, or to an online/offline
# mode the caller did not choose.
#
# The offline /3 test flag (-Dquicraft.multistream.offlineTest=true) is added ONLY when BOTH
# FABRIC_ONLINE_MODE=false and REAL_FABRIC_MULTISTREAM=true. A caller-supplied value of that
# system property is refused.
#
# Container fixture only: no production entrypoint or production default setting is changed.
set -eu

fail() {
    echo "real-client-entrypoint: $*" >&2
    exit 1
}

online="${FABRIC_ONLINE_MODE:?FABRIC_ONLINE_MODE must be set explicitly to true or false}"
multistream="${REAL_FABRIC_MULTISTREAM:?REAL_FABRIC_MULTISTREAM must be set explicitly to true or false}"
case "$online" in
    true | false) ;;
    *) fail "FABRIC_ONLINE_MODE must be true or false, got '$online'" ;;
esac
case "$multistream" in
    true | false) ;;
    *) fail "REAL_FABRIC_MULTISTREAM must be true or false, got '$multistream'" ;;
esac

# Refuse a caller-supplied override from every place the JVM or the base entrypoint reads
# options. Arguments are preserved for the base entrypoint, which ignores them; check them
# anyway so a flag that arrives there cannot look as if it took effect.
for option_source in "${JAVA_OPTS:-}" "${JAVA_TOOL_OPTIONS:-}" \
    "${JDK_JAVA_OPTIONS:-}" "${_JAVA_OPTIONS:-}" "$*"; do
    case "$option_source" in
        *-Dquicraft.multistream.offlineTest*)
            fail "refusing caller-supplied -Dquicraft.multistream.offlineTest override" ;;
    esac
done

[ -r /proc/self/mountinfo ] || fail "cannot inspect /proc/self/mountinfo; refusing to guess"

# Refuse any mount covering /srv or the config tree: a mount there can carry a pre-existing,
# shared or caller-owned config and is not a fresh testkit fixture. Field 5 of mountinfo is the
# mount point; spaces and similar characters are backslash-escaped there, never literal.
while read -r mnt_id parent_id major_minor root mount_point rest; do
    case "$mount_point" in
        /srv | /srv/config | /srv/config/*)
            fail "refusing mount at '$mount_point': /srv and /srv/config must come from the image" ;;
    esac
done < /proc/self/mountinfo

config_dir=/srv/config/quicraft
config_file=$config_dir/server.properties
if [ -e "$config_file" ] || [ -L "$config_file" ]; then
    fail "refusing pre-existing $config_file; start from a fresh container"
fi

umask 077
mkdir -p "$config_dir" || fail "cannot create $config_dir"
# A new file, never an edit: only the one switch this fixture owns. The mod supplies its
# documented defaults for everything else. Noclobber makes an unexpected race fail instead of
# overwriting a config that appeared after the check above.
if ! (set -C; printf 'multistream=%s\n' "$multistream" > "$config_file"); then
    fail "refusing to overwrite $config_file"
fi

coalesce="${REAL_FABRIC_COALESCE:-false}"
case "$coalesce" in
    true) JAVA_OPTS="-Dquicraft.coalesceWrites=true${JAVA_OPTS:+ $JAVA_OPTS}"; export JAVA_OPTS
          echo "real-client-entrypoint: write coalescing experiment on" >&2 ;;
    false) ;;
    *) fail "REAL_FABRIC_COALESCE must be true or false, got '$coalesce'" ;;
esac
coalesce_max="${REAL_FABRIC_COALESCE_MAX:-}"
case "$coalesce_max" in
    '') ;;
    *[!0-9]*) fail "REAL_FABRIC_COALESCE_MAX must be a byte count, got '$coalesce_max'" ;;
    *) JAVA_OPTS="-Dquicraft.coalesceMaxBytes=$coalesce_max${JAVA_OPTS:+ $JAVA_OPTS}"; export JAVA_OPTS ;;
esac

if [ "$online" = false ] && [ "$multistream" = true ]; then
    JAVA_OPTS="-Dquicraft.multistream.offlineTest=true${JAVA_OPTS:+ $JAVA_OPTS}"
    export JAVA_OPTS
    echo "real-client-entrypoint: offline mode with an explicit multistream test profile" >&2
else
    echo "real-client-entrypoint: online=$online multistream=$multistream" >&2
fi

# Confirmed base entrypoint path from docker/fabric/Dockerfile. Preserve original arguments.
exec /usr/local/bin/fabric-entrypoint.sh "$@"
