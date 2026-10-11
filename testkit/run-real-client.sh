#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The real Fabric client (26.1.2, ExternalServerGameTest) against the testkit's Velocity ->
# Paper: a first join over QUIC, then a rejoin with 0-RTT. Velocity logs each QUIC connection's
# stats with its early-release count (-Dquicraft.logStats); the rejoin must raise it by one.
#
#   testkit/run-real-client.sh            # offline mode (the gametest client has no Mojang account)
#
# Needs a display or xvfb-run, docker, and port 25565 free on 127.0.0.1 (TCP and UDP).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
compose=(docker compose -f "$here/docker/compose.yaml" -f "$here/docker/compose.real-client.yaml")
export VELOCITY_ONLINE_MODE=false QUICRAFT_PLUGIN=true QUICRAFT_JAVA_OPTS="-Dquicraft.logStats=true"

log="${REAL_CLIENT_LOG:-$here/results/real-client-velocity.log}"
cleanup() {
    mkdir -p "$(dirname "$log")"
    "${compose[@]}" logs --no-color velocity > "$log" 2>&1 || true
    echo "Velocity's log: $log"
    "${compose[@]}" down --timeout 60 >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$root/gradlew" -p "$root" -q :velocity:testkitPlugin
"${compose[@]}" build --quiet paper velocity mocksession
"${compose[@]}" up -d mocksession paper velocity
for svc in paper velocity; do
    deadline=$((SECONDS + 600))
    until "${compose[@]}" logs --no-color "$svc" 2>/dev/null | grep -cF 'Done (' >/dev/null; do
        ((SECONDS < deadline)) || { echo "timed out waiting for $svc" >&2; exit 1; }
        sleep 2
    done
done

xvfb=()
[[ -z "${DISPLAY:-}" ]] && xvfb=(xvfb-run -a -s "-screen 0 1280x720x24")
ALSOFT_DRIVERS=null "${xvfb[@]}" "$root/gradlew" -p "$root" :fabric-26x:runClientGameTest \
    -Pquicraft.externalServer=127.0.0.1:25565 --console=plain

echo "== Velocity's QUIC connections (earlyReleases counts logins passed on 0-RTT data):"
stats="$("${compose[@]}" logs --no-color velocity | grep -F 'QUICraft stats' || true)"
echo "$stats"
first="$(echo "$stats" | grep -o 'earlyReleases=[0-9-]*' | head -1 | cut -d= -f2)"
last="$(echo "$stats" | grep -o 'earlyReleases=[0-9-]*' | tail -1 | cut -d= -f2)"
if [[ "$(echo "$stats" | grep -c .)" -lt 2 || "$first" != 0 || "$last" != 1 ]]; then
    echo "FAILED: expected two QUIC connections, the rejoin passed on 0-RTT data (earlyReleases 0 then 1)" >&2
    exit 1
fi
echo "OK: the rejoin's 0-RTT data was acted on by Velocity"
