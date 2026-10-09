#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Image build step: starts Paper once with Chunky, generates a square of RADIUS blocks around
# world spawn, stops Paper and removes Chunky. Every testkit run then starts from the same world
# with all chunks the bench can see already on disk, so chunk-load time measures sending, not
# world generation.
set -eu
radius="${1:?radius in blocks}"
log=/tmp/pregen.log

cd /srv
mkfifo /tmp/console
java -Xms2G -Xmx2G -jar paper.jar --nogui < /tmp/console > "$log" 2>&1 &
pid=$!
# Hold the console pipe open; Paper would see EOF otherwise.
exec 3>/tmp/console

wait_log() { # fixed string, timeout in seconds
    waited=0
    until grep -qF "$1" "$log"; do
        if ! kill -0 "$pid" 2>/dev/null || [ "$waited" -ge "$2" ]; then
            echo "pregen: gave up waiting for '$1'" >&2
            tail -n 50 "$log" >&2
            exit 1
        fi
        waited=$((waited + 1))
        sleep 1
    done
}

wait_log 'Done (' 600
for command in "chunky spawn" "chunky shape square" "chunky radius $radius" "chunky start"; do
    echo "$command" >&3
    sleep 1
done
wait_log 'Task finished for' 3600
grep -F 'Task finished for' "$log"
echo stop >&3
wait "$pid"
exec 3>&-

# Chunky only builds the world; the measured server is plain Paper.
rm -rf plugins/Chunky.jar plugins/Chunky logs /tmp/console
