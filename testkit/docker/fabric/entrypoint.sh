#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# FABRIC_ONLINE_MODE=true runs online-mode logins against the mock session server, which turns
# on Minecraft's AES/CFB8 encryption like a real server. QUICRAFT_PLUGIN=false leaves the mod
# out, for a vanilla Fabric baseline.
set -eu
online="${FABRIC_ONLINE_MODE:-true}"
sed -i "s/^online-mode=.*/online-mode=${online}/" /srv/server.properties
mkdir -p /srv/mods
rm -f /srv/mods/quicraft-fabric.jar
if [ "${QUICRAFT_PLUGIN:-true}" = true ]; then
    cp /srv/quicraft/quicraft-fabric.jar /srv/mods/
fi
# 26.1.2 ignores these unless all three are set.
mock="${MOCK_SESSION_URL:-http://mocksession:8080}"
# shellcheck disable=SC2086 # JAVA_OPTS is a list of flags
exec java -Xms2G -Xmx2G ${JAVA_OPTS:-} \
    -Dminecraft.api.session.host="$mock" -Dminecraft.api.services.host="$mock" \
    -Dminecraft.api.profiles.host="$mock" \
    -jar /srv/fabric-server-launch.jar --nogui
