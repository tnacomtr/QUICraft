#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds QUICraft's patched Netty QUIC jars for Linux on this host's architecture, in Docker
# (natives/Dockerfile). Output goes to natives/build/out/; core/build.gradle.kts reads
# natives/build/out/repo. Caches (Maven, cargo, the BoringSSL build, the quiche clone) stay in
# natives/build/ between runs. Nothing here is committed.
#
#   natives/build-linux.sh                  # ~15 min the first time, less with warm caches
#   SKIP_PATCHES=1 natives/build-linux.sh   # unpatched upstream sources (pipeline check only)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
build="$here/build"
image="quicraft-netty-quic-build:$(sha256sum "$here/Dockerfile" | cut -c1-12)"
mkdir -p "$build/work" "$build/out" "$build/cache/cargo" "$build/cache/m2" "$build/cache/home"

docker build -t "$image" "$here"
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/work/cache/home \
    -e CARGO_HOME=/work/cache/cargo \
    -e MAVEN_USER_HOME=/work/cache/m2/wrapper \
    -e MAVEN_REPO_LOCAL=/work/cache/m2/repository \
    -e PYTHON=/usr/libexec/platform-python \
    -e SKIP_PATCHES="${SKIP_PATCHES:-0}" \
    -v "$here:/src:ro" \
    -v "$build:/work" \
    "$image" \
    bash /src/build-netty-quic.sh /src /work/work /work/out
