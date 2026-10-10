#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Image build step: starts the Fabric server once (no mods), generates the chunks around world
# spawn with vanilla's /forceload, tile by tile, checks each tile with `execute if loaded`, then
# saves and stops. Every testkit run then starts from the same world with all chunks the bench
# can see already on disk, so chunk-load time measures sending, not world generation. Same seed
# as the Paper backend.
set -eu
tiles="${1:?tiles per side (16x16 chunks each, centred on spawn)}"
log=/tmp/pregen.log

cd /srv
mkfifo /tmp/console
java -Xms2G -Xmx2G -jar fabric-server-launch.jar --nogui < /tmp/console > "$log" 2>&1 &
pid=$!
# Hold the console pipe open; the server would see EOF otherwise.
exec 3>/tmp/console

wait_count() { # fixed string, count, timeout in seconds
    waited=0
    until [ "$(grep -cF "$1" "$log")" -ge "$2" ]; do
        if ! kill -0 "$pid" 2>/dev/null || [ "$waited" -ge "$3" ]; then
            echo "pregen: gave up waiting for '$1' x$2" >&2
            tail -n 50 "$log" >&2
            exit 1
        fi
        waited=$((waited + 1))
        sleep 1
    done
}

wait_count 'Done (' 1 600
# Console commands run at world spawn, so ~ is relative to it. Tiles of 16x16 chunks (the
# /forceload limit is 256 chunks per command).
half=$((tiles * 256 / 2))
passed=0
tz=0
while [ "$tz" -lt "$tiles" ]; do
    tx=0
    while [ "$tx" -lt "$tiles" ]; do
        x0=$((tx * 256 - half))
        z0=$((tz * 256 - half))
        x1=$((x0 + 255))
        z1=$((z0 + 255))
        echo "forceload add ~$x0 ~$z0 ~$x1 ~$z1" >&3
        # Corners and centre of the tile must be loaded (generated) before moving on.
        for x in $x0 $((x0 + 128)) $x1; do
            for z in $z0 $((z0 + 128)) $z1; do
                until echo "execute if loaded ~$x 64 ~$z" >&3 && sleep 0.2 \
                        && [ "$(grep -cF 'Test passed' "$log")" -gt "$passed" ]; do
                    if ! kill -0 "$pid" 2>/dev/null; then
                        tail -n 50 "$log" >&2
                        exit 1
                    fi
                    sleep 1
                done
                passed=$((passed + 1))
            done
        done
        echo "forceload remove ~$x0 ~$z0 ~$x1 ~$z1" >&3
        tx=$((tx + 1))
    done
    tz=$((tz + 1))
done
echo "pregen: $((tiles * tiles)) tiles of 16x16 chunks generated"
echo "save-all flush" >&3
wait_count 'Saved the game' 1 600
echo stop >&3
wait "$pid"
