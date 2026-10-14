#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# End-to-end test of the server updater against a real server, on Linux and macOS. It is the
# evidence for docs/updater.md blocker 10: a live atomic jar swap on Linux and macOS, including
# JDK 21.
#
#   testkit/run-updater-e2e.sh <velocity|fabric-1.21.11|fabric-26x>
#
# One run, with every step asserted. The server logs, the console transcript, the mock release
# host's log and this summary stay in the result directory, and CI uploads it on failure:
#
#   1. Build two jars of the module with the development trust file (the default; the release
#      trust property is never passed): OLD 0.1.0-alpha.1 and NEW 0.1.0-alpha.2, copied out after
#      each build. One Gradle build at a time.
#   2. Start the mock release host (updater test sources) on three ephemeral localhost HTTPS
#      ports, serving the signed NEW release and the revocations document with the RFC 8032 TEST
#      keys the development trust file carries, and export its TLS truststore.
#   3. Install OLD and start the server with -Dquicraft.updater.devOrigins pointing at those three
#      ports, with the exported truststore, and the console on a pipe. Wait for the startup line.
#   4. Console: "quicraft update check" must offer 0.1.0-alpha.2, then "quicraft update" must take
#      the swap path. Assert: the live jar's SHA-512 is NEW's, a backup of OLD is in the updater's
#      backup directory, and a TCP server-list status ping still answers (testkit/status-ping.py).
#   5. Stop cleanly ("end" for Velocity, "stop" for Fabric): exit code 0 and no exception stack
#      frame mentioning rs.sudoe.quicraft in the log.
#   6. Start again with the same flags. Assert: the log names the new version, the log reports the
#      updater's health completion, a status ping answers, and "quicraft update check" reports no
#      update. Stop cleanly again.
#
# Requirements: JDK 25 (and JDK 21 for the 1.21.11 Fabric server; when JDK 21 is missing, that
# server runs on JDK 25 and the run says so), python3, curl or wget, a network for the server jars
# (Velocity's is checked against its sha256, the Fabric launcher downloads the Mojang server jar on
# its first start), and QUICraft's patched Netty QUIC build under natives/build/out/repo
# (natives/build-linux.sh). macOS bash 3.2 and BSD tools only: no readarray, no ${x,,}, no sed -i,
# no timeout, no sha512sum.
#
# Environment:
#   JAVA21, JAVA25        the JDK 21 and JDK 25 java launchers or JAVA_HOMEs for the server JVMs
#   QUICRAFT_E2E_CACHE    download cache (default ~/.cache/quicraft-updater-e2e)
#   QUICRAFT_E2E_KEEP     keep the mock release host running when set (for hand debugging)
#
# Results: testkit/results/updater-e2e-<server>-<UTC timestamp>/ (gitignored). Paths with spaces
# are not supported: the JVM flags are passed as a word list.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

OLD_VERSION=0.1.0-alpha.1
NEW_VERSION=0.1.0-alpha.2
TRUSTSTORE_PASSWORD=quicraft-test-only
STARTUP_LINE='Done ('
HEALTHY_LINE='new version passed the health check; update journal cleared'
FABRIC_LOADER=0.19.5
FABRIC_INSTALLER=1.1.2
VELOCITY_VERSION=4.2.0-30
VELOCITY_SHA256=35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8
VELOCITY_URL="https://fill-data.papermc.io/v1/objects/$VELOCITY_SHA256/velocity-$VELOCITY_VERSION.jar"

server_kind="${1:-}"
case "$server_kind" in
    velocity|fabric-1.21.11|fabric-26x) ;;
    *)
        echo "usage: testkit/run-updater-e2e.sh <velocity|fabric-1.21.11|fabric-26x>" >&2
        exit 2
        ;;
esac

case "$server_kind" in
    velocity)
        module=velocity
        platform=velocity
        target=4.2
        want_jdk=25
        startup_timeout=300
        ;;
    fabric-1.21.11)
        module=fabric-1.21.11
        platform=fabric-1.21.11
        target=1.21.11
        want_jdk=21
        startup_timeout=900
        ;;
    fabric-26x)
        module=fabric-26x
        platform=fabric-26x
        target=26.1.2
        want_jdk=25
        startup_timeout=900
        ;;
esac

stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
out="$here/results/updater-e2e-$server_kind-$stamp"
summary="$out/summary.txt"
srv="$out/server"
cache="${QUICRAFT_E2E_CACHE:-$HOME/.cache/quicraft-updater-e2e}"
mkdir -p "$out" "$cache"

update_timeout=300
stop_timeout=180
ping_timeout=20

mock_pid=""
server_pid=""
log_file=""
server_fifo=""

say() {
    echo "$*"
    echo "$*" >> "$summary"
}

pass() {
    say "PASS: $*"
}

fail() {
    say "FAIL: $*"
    echo "$*" > "$out/FAILED"
    if [ -n "$log_file" ] && [ -f "$log_file" ]; then
        echo "---- tail of $log_file" >> "$summary"
        tail -n 80 "$log_file" >> "$summary" 2>/dev/null || true
    fi
    exit 1
}

cleanup() {
    if [ -n "$server_pid" ] && kill -0 "$server_pid" 2>/dev/null; then
        echo "e2e: killing the server (pid $server_pid)" >> "$summary"
        kill "$server_pid" 2>/dev/null || true
    fi
    if [ -n "$mock_pid" ] && [ -z "${QUICRAFT_E2E_KEEP:-}" ] && kill -0 "$mock_pid" 2>/dev/null; then
        echo "e2e: killing the mock release host (pid $mock_pid)" >> "$summary"
        kill "$mock_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ---- tools ------------------------------------------------------------------------------------

java_major() {
    "$1" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p'
}

resolve_java() {
    if [ -z "${1:-}" ]; then
        return 1
    fi
    if [ -x "$1" ]; then
        printf '%s\n' "$1"
        return 0
    fi
    if [ -x "$1/bin/java" ]; then
        printf '%s\n' "$1/bin/java"
        return 0
    fi
    return 1
}

candidate_javas() {
    for d in "$HOME"/.gradle/jdks/*/ "$HOME"/.gradle/jdks/*/*/ /usr/lib/jvm/*/ \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/ /opt/homebrew/opt/openjdk*/ \
        /usr/local/opt/openjdk*/; do
        if [ -x "$d/bin/java" ]; then
            printf '%s\n' "$d/bin/java"
        fi
    done
}

find_java() {
    for candidate in $(candidate_javas); do
        if [ "$(java_major "$candidate")" = "$1" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# The java launcher for one major version: JAVA21/JAVA25 first, then the usual install locations.
pick_java() {
    if resolved="$(resolve_java "${2:-}")"; then
        printf '%s\n' "$resolved"
        return 0
    fi
    find_java "$1"
}

free_port() {
    python3 -c 'import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()'
}

sha512_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 512 "$1" | cut -d' ' -f1
    elif command -v sha512sum >/dev/null 2>&1; then
        sha512sum "$1" | cut -d' ' -f1
    else
        return 1
    fi
}

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        return 1
    fi
}

download() {
    if [ -s "$2" ]; then
        return 0
    fi
    echo "e2e: downloading $1"
    rm -f "$2.part"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2.part" || return 1
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$1" -O "$2.part" || return 1
    else
        return 1
    fi
    mv "$2.part" "$2"
    return 0
}

# A portable wait: macOS has no timeout(1). Stops early when the watched process is gone.
wait_for_log() {
    waited=0
    while [ "$waited" -lt "$3" ]; do
        if [ -f "$1" ] && grep -qF "$2" "$1"; then
            return 0
        fi
        if [ -n "${4:-}" ] && ! kill -0 "$4" 2>/dev/null; then
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# ---- preflight --------------------------------------------------------------------------------

command -v python3 >/dev/null 2>&1 || fail "python3 is required (the status ping and the free port)"
if ! command -v shasum >/dev/null 2>&1 && ! command -v sha512sum >/dev/null 2>&1; then
    fail "neither shasum nor sha512sum is available"
fi
say "server: $server_kind (module :$module, platform $platform, target $target)"
say "results: $out"

if [ "$want_jdk" = 21 ]; then env_java_raw="${JAVA21:-}"; else env_java_raw="${JAVA25:-}"; fi
if server_java="$(pick_java "$want_jdk" "$env_java_raw")"; then
    server_major="$(java_major "$server_java")"
    say "server JVM ($want_jdk): $server_java ($server_major)"
else
    if [ "$want_jdk" = 21 ] && server_java="$(pick_java 25 "${JAVA25:-}")"; then
        server_major="$(java_major "$server_java")"
        say "NOTE: JDK 21 is not installed here; running $server_kind on JDK $server_major"
    else
        server_java="$(pick_java 25 "${JAVA25:-}")" || server_java="java"
        server_major="$(java_major "$server_java")"
        say "NOTE: no JDK $want_jdk found; using $server_java ($server_major)"
    fi
fi

# The mock release host and the updater test classes need Java 21 or newer; the server JVM is one.
mock_java="$(pick_java 25 "${JAVA25:-}")" || mock_java="$server_java"

gradle_install_args=""
if [ -n "${JAVA_HOME_25_X64:-}${JAVA_HOME_25_ARM64:-}${JAVA_HOME_21_X64:-}${JAVA_HOME_21_ARM64:-}" ]; then
    gradle_install_args="-Porg.gradle.java.installations.fromEnv=JAVA_HOME_21_X64,JAVA_HOME_21_ARM64,JAVA_HOME_25_X64,JAVA_HOME_25_ARM64 -Porg.gradle.java.installations.auto-download=false"
fi

# ---- 1. the two jars ---------------------------------------------------------------------------

build_jar() {
    version="$1"
    destination="$2"
    say "building :$module:$version (one Gradle build at a time)"
    # shellcheck disable=SC2086 # gradle_install_args is a list of CI-only toolchain flags
    if ! "$root/gradlew" -p "$root" --console=plain ":$module:build" -x test \
        "-Pversion=$version" -Pquicraft.allowDevTrust=true $gradle_install_args \
        > "$out/build-$version.log" 2>&1; then
        tail -n 30 "$out/build-$version.log" >&2
        fail "the :$module build for $version failed; see $out/build-$version.log"
    fi
    case "$module" in
        velocity) jar="$root/velocity/build/libs/velocity-$version.jar" ;;
        fabric-26x) jar="$root/fabric-26x/build/libs/quicraft-fabric-26x-$version.jar" ;;
        fabric-1.21.11) jar="$root/fabric-1.21.11/build/libs/quicraft-fabric-1.21.11-$version.jar" ;;
    esac
    if [ ! -f "$jar" ]; then
        ls "$root/$module/build/libs" >&2 || true
        fail "expected $jar after the $version build; see $out/build-$version.log"
    fi
    cp "$jar" "$destination"
    say "built $destination from $jar"
}

mkdir -p "$out/jars"
build_jar "$OLD_VERSION" "$out/jars/old.jar"
build_jar "$NEW_VERSION" "$out/jars/new.jar"
old_sha="$(sha512_of "$out/jars/old.jar")"
new_sha="$(sha512_of "$out/jars/new.jar")"
say "OLD $OLD_VERSION sha512=$old_sha"
say "NEW $NEW_VERSION sha512=$new_sha"

# The mock release host runs from the updater's test sources (MockReleaseHostMain).
# shellcheck disable=SC2086 # gradle_install_args is a list of CI-only toolchain flags
if ! "$root/gradlew" -p "$root" --console=plain :updater:testClasses $gradle_install_args \
    > "$out/build-updater.log" 2>&1; then
    tail -n 30 "$out/build-updater.log" >&2
    fail "the :updater:testClasses build failed; see $out/build-updater.log"
fi
updater_cp="$root/updater/build/classes/java/test:$root/updater/build/resources/test"
updater_cp="$updater_cp:$root/updater/build/classes/java/main:$root/updater/build/resources/main"

# ---- 2. the mock release host ------------------------------------------------------------------

say "starting the mock release host (new jar, three ephemeral HTTPS ports)"
"$mock_java" -cp "$updater_cp" rs.sudoe.quicraft.updater.MockReleaseHostMain \
    --jar "$out/jars/new.jar" --version "$NEW_VERSION" --platform "$platform" \
    --targets "$target" --ports 0,0,0 --export-truststore "$out/truststore.p12" \
    > "$out/mock-host.log" 2>&1 &
mock_pid=$!
if ! wait_for_log "$out/mock-host.log" 'MOCK READY' 60 "$mock_pid"; then
    cat "$out/mock-host.log" >&2 || true
    fail "the mock release host did not become ready; see $out/mock-host.log"
fi
ready_line="$(grep -F 'MOCK READY' "$out/mock-host.log" | head -n 1)"
mock_ports="$(printf '%s' "$ready_line" | sed -n 's/.*ports=\([0-9][0-9,]*\).*/\1/p')"
case "$mock_ports" in
    *,*,*) ;;
    *) fail "the mock release host did not report three ports: $ready_line" ;;
esac
port1="${mock_ports%%,*}"
port_rest="${mock_ports#*,}"
port2="${port_rest%%,*}"
port3="${port_rest#*,}"
say "$ready_line"
say "mock origin ports: modrinth=$port1 github=$port2 sudoe=$port3"
if [ ! -f "$out/truststore.p12" ]; then
    fail "the mock release host did not export its truststore"
fi

origins="modrinth=https://localhost:$port1,github=https://localhost:$port2,sudoe=https://localhost:$port3"
server_jvm_args="-Dquicraft.updater.devOrigins=$origins -Djavax.net.ssl.trustStore=$out/truststore.p12 -Djavax.net.ssl.trustStorePassword=$TRUSTSTORE_PASSWORD"

# ---- the server directory ----------------------------------------------------------------------

game_port="$(free_port)"
say "game port: $game_port"
if [ "$server_kind" = velocity ]; then
    live_jar="$srv/plugins/quicraft-velocity.jar"
    update_dir="$srv/plugins/quicraft/update"
else
    live_jar="$srv/mods/quicraft.jar"
    update_dir="$srv/config/quicraft/update"
fi

download "$VELOCITY_URL" "$cache/velocity-$VELOCITY_VERSION.jar" || fail "cannot download the Velocity jar"
velocity_sha="$(sha256_of "$cache/velocity-$VELOCITY_VERSION.jar")"
if [ "$velocity_sha" != "$VELOCITY_SHA256" ]; then
    rm -f "$cache/velocity-$VELOCITY_VERSION.jar"
    download "$VELOCITY_URL" "$cache/velocity-$VELOCITY_VERSION.jar" || fail "cannot download the Velocity jar"
    velocity_sha="$(sha256_of "$cache/velocity-$VELOCITY_VERSION.jar")"
    if [ "$velocity_sha" != "$VELOCITY_SHA256" ]; then
        fail "the Velocity jar has sha256 $velocity_sha, want $VELOCITY_SHA256"
    fi
fi
say "Velocity $VELOCITY_VERSION sha256 verified: $velocity_sha"

if [ "$server_kind" = velocity ]; then
    mkdir -p "$srv/plugins"
    cp "$cache/velocity-$VELOCITY_VERSION.jar" "$srv/velocity.jar"
    cp "$out/jars/old.jar" "$live_jar"
    cat > "$srv/velocity.toml" <<EOF
config-version = "2.9"
bind = "127.0.0.1:$game_port"
motd = "QUICraft updater end-to-end test"
show-max-players = 50
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "NONE"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = true

[servers]

[forced-hosts]

[advanced]
compression-threshold = 256
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000

[query]
enabled = false
EOF
    printf '%s' "quicraft-updater-e2e-secret" > "$srv/forwarding.secret"
else
    mkdir -p "$srv/mods"
    cp "$out/jars/old.jar" "$live_jar"
    launcher="$cache/fabric-server-$target-$FABRIC_LOADER-$FABRIC_INSTALLER.jar"
    download "https://meta.fabricmc.net/v2/versions/loader/$target/$FABRIC_LOADER/$FABRIC_INSTALLER/server/jar" \
        "$launcher" || fail "cannot download the Fabric server launcher for $target"
    cp "$launcher" "$srv/fabric-server-launch.jar"
    # The Mojang EULA, accepted for the testkit by the project owner on 2026-10-09.
    printf 'eula=true\n' > "$srv/eula.txt"
    cat > "$srv/server.properties" <<EOF
server-port=$game_port
online-mode=false
enforce-secure-profile=false
enable-query=false
level-seed=quicraft-updater-e2e
view-distance=6
simulation-distance=6
gamemode=creative
force-gamemode=true
difficulty=peaceful
allow-flight=true
spawn-protection=0
max-players=50
network-compression-threshold=256
EOF
fi
say "installed OLD $OLD_VERSION as $live_jar"
say "updater directory: $update_dir"

# ---- start, console and stop -------------------------------------------------------------------

console_line() {
    say "console> $1"
    printf '%s\n' "$1" >&3
}

start_server() {
    number="$1"
    log_file="$out/server-$number.log"
    server_fifo="$out/console-$number.fifo"
    rm -f "$server_fifo" "$log_file"
    mkfifo "$server_fifo"
    if [ "$server_kind" = velocity ]; then
        # shellcheck disable=SC2086 # server_jvm_args is a list of -D flags
        ( cd "$srv" && exec "$server_java" -Xms512M -Xmx1G $server_jvm_args -jar velocity.jar ) \
            < "$server_fifo" > "$log_file" 2>&1 &
    else
        # shellcheck disable=SC2086 # server_jvm_args is a list of -D flags
        ( cd "$srv" && exec "$server_java" -Xms1G -Xmx1G $server_jvm_args \
            -jar fabric-server-launch.jar --nogui ) < "$server_fifo" > "$log_file" 2>&1 &
    fi
    server_pid=$!
    # Read-write, so opening the FIFO never blocks, and keep it open for this start's console.
    exec 3<> "$server_fifo"
    say "start $number: pid $server_pid, console $server_fifo, log $log_file"
    if ! wait_for_log "$log_file" "$STARTUP_LINE" "$startup_timeout" "$server_pid"; then
        fail "start $number: the server did not reach '$STARTUP_LINE' (see $log_file)"
    fi
    pass "start $number: startup finished"
}

check_no_stack_trace() {
    number="$1"
    if grep -E '^[[:space:]]+at rs\.sudoe\.quicraft' "$log_file" > "$out/stack-$number.txt" 2>/dev/null; then
        fail "log $number has an exception stack frame mentioning rs.sudoe.quicraft (see $out/stack-$number.txt)"
    fi
    rm -f "$out/stack-$number.txt"
    pass "log $number: no exception stack frame mentions rs.sudoe.quicraft"
}

stop_server() {
    number="$1"
    if [ "$server_kind" = velocity ]; then
        stop_word=end
    else
        stop_word=stop
    fi
    console_line "$stop_word"
    # A polling loop, so a hung server cannot hang CI: macOS has no timeout(1), and no background
    # watchdog is left behind holding this step's output pipes open.
    waited=0
    while [ "$waited" -lt "$stop_timeout" ]; do
        if ! kill -0 "$server_pid" 2>/dev/null; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
    if kill -0 "$server_pid" 2>/dev/null; then
        say "stop $number: the server did not stop within ${stop_timeout}s; killing pid $server_pid"
        kill -9 "$server_pid" 2>/dev/null || true
    fi
    status=0
    wait "$server_pid" || status=$?
    server_pid=""
    say "stop $number: exit code $status"
    if [ "$status" != 0 ]; then
        fail "stop $number: exit code $status, want 0 (see $log_file)"
    fi
    pass "stop $number: clean exit 0"
    check_no_stack_trace "$number"
}

status_ping() {
    label="$1"
    if ! python3 "$here/status-ping.py" 127.0.0.1 "$game_port" "$ping_timeout" \
        > "$out/status-$label.json" 2> "$out/status-$label.err"; then
        cat "$out/status-$label.err" >&2 || true
        fail "the TCP status ping ($label) did not answer"
    fi
    ping_head="$(head -c 120 "$out/status-$label.json")"
    pass "TCP status ping answers ($label): $ping_head"
}

# ---- 3. and 4. first start: check, swap, backup, ping ------------------------------------------

start_server 1
console_line 'quicraft update check'
if ! wait_for_log "$log_file" "update available: $NEW_VERSION" "$update_timeout" "$server_pid"; then
    fail "the check did not offer $NEW_VERSION (see $log_file)"
fi
pass "check offers $NEW_VERSION"

console_line 'quicraft update'
if ! wait_for_log "$log_file" 'update finished: INSTALLED_RESTART_PENDING' "$update_timeout" "$server_pid"; then
    fail "the update did not finish with INSTALLED_RESTART_PENDING (see $log_file)"
fi
pass "update took the swap path (installed, restart required)"

live_sha="$(sha512_of "$live_jar")"
if [ "$live_sha" != "$new_sha" ]; then
    say "live jar sha512=$live_sha want=$new_sha"
    fail "the live jar is not the new one: $live_jar"
fi
pass "the live jar is NEW ($live_sha)"

found_backup=0
for candidate in "$update_dir"/backup/*.jar; do
    if [ -f "$candidate" ] && [ "$(sha512_of "$candidate")" = "$old_sha" ]; then
        found_backup=1
        say "backup of OLD: $candidate"
    fi
done
if [ "$found_backup" != 1 ]; then
    ls -R "$update_dir" >&2 || true
    fail "no backup of OLD ($old_sha) in $update_dir/backup"
fi
pass "a backup of OLD is in $update_dir/backup"

status_ping after-update
stop_server 1

# ---- 5. and 6. second start: the new version runs, healthy, no update ---------------------------

start_server 2
grep -F "$NEW_VERSION" "$log_file" > "$out/version-2.txt" 2>/dev/null || true
if [ ! -s "$out/version-2.txt" ]; then
    fail "start 2: the log does not name $NEW_VERSION (see $log_file)"
fi
version_line="$(head -n 1 "$out/version-2.txt")"
pass "start 2: the log names the new version: $version_line"

if ! wait_for_log "$log_file" "$HEALTHY_LINE" 60 "$server_pid"; then
    fail "start 2: the updater did not report the health completion (see $log_file)"
fi
healthy_line="$(grep -F "$HEALTHY_LINE" "$log_file" | head -n 1)"
pass "start 2: the updater reached healthy: $healthy_line"

status_ping after-restart
console_line 'quicraft update check'
if ! wait_for_log "$log_file" 'no update' "$update_timeout" "$server_pid"; then
    fail "start 2: the check did not report no update (see $log_file)"
fi
pass "start 2: the check reports no update"
stop_server 2

say "RESULT: PASS ($server_kind)"
say "results: $out"
