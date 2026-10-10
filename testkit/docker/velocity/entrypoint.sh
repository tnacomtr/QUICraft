#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# VELOCITY_ONLINE_MODE=true runs online-mode logins against the mock session server, which
# turns on Minecraft's AES/CFB8 encryption like a real server.
set -eu
online="${VELOCITY_ONLINE_MODE:-true}"
sed "s/@ONLINE_MODE@/${online}/" /srv/velocity.toml.template > /srv/velocity.toml
printf '%s' "quicraft-testkit-forwarding-secret" > /srv/forwarding.secret
mkdir -p /srv/plugins
rm -f /srv/plugins/quicraft-velocity.jar
if [ "${QUICRAFT_PLUGIN:-true}" = true ]; then
    cp /srv/quicraft/quicraft-velocity.jar /srv/plugins/
fi
exec java -Xms1G -Xmx1G \
    -Dmojang.sessionserver="${MOCK_SESSION_URL:-http://mocksession:8080}/session/minecraft/hasJoined" \
    -jar /srv/velocity.jar
