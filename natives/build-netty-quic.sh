#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds QUICraft's patched Netty QUIC jars for the platform it runs on:
#   rs.sudoe.quicraft.netty:netty-codec-classes-quic:<version>
#   rs.sudoe.quicraft.netty:netty-codec-native-quic:<version>:<classifier>
# Netty's own Maven build runs unchanged (same flags, BoringSSL and quiche build steps, licence
# files and manifest entries as the upstream release). Only the patches in natives/patches/ and
# a pinned quiche Cargo.lock are added.
#
#   natives/build-netty-quic.sh <natives-dir> <work-dir> <out-dir>
#
# Linux: run through natives/build-linux.sh (Docker). macOS/Windows: run on a native host with the
# toolchain from .github/workflows/netty-quic-natives.yml.
#
# Output (<out-dir>): repo/ (Maven layout, read by core/build.gradle.kts), licenses/ (crate
# inventory), build-info-<classifier>.txt, quiche-Cargo.lock (the lock file used).
#
# Env: SKIP_PATCHES=1 builds the upstream sources unchanged, to tell pipeline failures from patch
# failures. Never use its output.
set -euo pipefail

NETTY_TAG=netty-4.2.19.Final
NETTY_COMMIT=64cc10f38ea5f5bd7eae48507817c66680d0afdc
NETTY_VERSION=4.2.19.Final
QUICHE_REPO=https://github.com/cloudflare/quiche
QUICHE_COMMIT=be47c5011215b9f13bad06bd7627d3ae49888a19
# Apache-2.0 since 33d1049b (2025-02-03); this pin is from 2026-05-12 (CLAUDE.md "Licensing").
BORINGSSL_COMMIT=d03dbc3e5d7de44183ff17018af22323af650fbc
# Bump the suffix whenever a patch, the lock file or a pin changes.
QUICRAFT_VERSION=4.2.19.Final-quicraft2
GROUP_PATH=rs/sudoe/quicraft/netty
QUICHE_FEATURES="ffi qlog custom-client-dcid"

[[ $# -eq 3 ]] || { echo "usage: $0 <natives-dir> <work-dir> <out-dir>" >&2; exit 2; }
src="$(cd "$1" && pwd)"
mkdir -p "$2" "$3"
work="$(cd "$2" && pwd)"
out="$(cd "$3" && pwd)"
python="${PYTHON:-python3}"
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }

case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=osx ;;
    MINGW* | MSYS* | CYGWIN*) os=windows ;;
    *) echo "unsupported OS $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64 | amd64) arch=x86_64 ;;
    aarch64 | arm64) arch=aarch_64 ;;
    *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
classifier="$os-$arch"
echo "== building $QUICRAFT_VERSION for $classifier"

netty="$work/netty"
native="$netty/codec-native-quic"

# --- Netty sources, pristine on every run. BoringSSL's build output is kept across runs: it is
# unpatched and keyed by its commit.
boring_cache="$work/boringssl-$BORINGSSL_COMMIT-$classifier"
if [[ ! -d "$netty/.git" ]]; then
    git clone -q --depth 1 --branch "$NETTY_TAG" https://github.com/netty/netty.git "$netty"
fi
[[ "$(git -C "$netty" rev-parse HEAD)" == "$NETTY_COMMIT" ]] \
    || { echo "netty checkout is not $NETTY_COMMIT" >&2; exit 1; }
if [[ -d "$native/target/boringssl" && ! -d "$boring_cache" ]]; then
    mv "$native/target/boringssl" "$boring_cache"
fi
git -C "$netty" reset -q --hard
# -ff: also nested repositories (the quiche checkout).
git -C "$netty" clean -q -ffdx
grep -q "<boringsslCommitSha>$BORINGSSL_COMMIT</boringsslCommitSha>" "$native/pom.xml" \
    || { echo "Netty's pom pins another BoringSSL commit" >&2; exit 1; }
grep -q "<quicheCommitSha>$QUICHE_COMMIT</quicheCommitSha>" "$native/pom.xml" \
    || { echo "Netty's pom pins another quiche commit" >&2; exit 1; }
mkdir -p "$native/target"
if [[ -d "$boring_cache" ]]; then
    cp -a "$boring_cache" "$native/target/boringssl"
fi

# --- quiche, pre-seeded where Netty's pom expects it (the pom then skips its own clone).
quiche_cache="$work/quiche.git"
if [[ ! -d "$quiche_cache" ]]; then
    git clone -q --bare "$QUICHE_REPO" "$quiche_cache"
fi
git -C "$quiche_cache" cat-file -e "$QUICHE_COMMIT^{commit}" 2>/dev/null \
    || git -C "$quiche_cache" fetch -q origin "$QUICHE_COMMIT"
quiche="$native/target/quiche-source"
git clone -q --no-checkout "$quiche_cache" "$quiche"
git -C "$quiche" checkout -q "$QUICHE_COMMIT"

if [[ "${SKIP_PATCHES:-0}" != 1 ]]; then
    for p in "$src"/patches/netty/*.patch; do
        echo "applying $(basename "$p")"
        git -C "$netty" apply --whitespace=error-all "$p"
    done
    for p in "$src"/patches/quiche/*.patch; do
        echo "applying $(basename "$p")"
        git -C "$quiche" apply --whitespace=error-all "$p"
    done
else
    echo "!! SKIP_PATCHES=1: building upstream sources unchanged"
fi

# quiche commits no Cargo.lock, so without one the crate versions depend on the build date.
if [[ -f "$src/quiche-Cargo.lock" ]]; then
    cp "$src/quiche-Cargo.lock" "$quiche/Cargo.lock"
    (cd "$quiche" && cargo fetch --locked)
else
    echo "!! no natives/quiche-Cargo.lock: generating one; commit $out/quiche-Cargo.lock"
    (cd "$quiche" && cargo generate-lockfile && cargo fetch --locked)
fi
cp "$quiche/Cargo.lock" "$out/quiche-Cargo.lock"

# --- Licence inventory of what the native links, checked against CLAUDE.md's table. Licence
# texts go under license/quiche-deps/, which Netty's pom copies into META-INF/license/.
mkdir -p "$out/licenses"
tree() {
    (cd "$quiche" && cargo tree --locked -p quiche --features "$QUICHE_FEATURES" --target all \
        --prefix none -f '{p}' "$@") | sed -e 's/ (\*)$//' -e 's/ (proc-macro)$//' | sort -u
}
tree -e normal,no-proc-macro > "$out/licenses/linked-crates.txt"
tree -e normal,build > "$out/licenses/all-crates.txt"
(cd "$quiche" && cargo metadata --locked --all-features --format-version 1) > "$work/cargo-metadata.json"
"$python" "$src/crate-licenses.py" \
    --metadata "$work/cargo-metadata.json" \
    --linked "$out/licenses/linked-crates.txt" \
    --all "$out/licenses/all-crates.txt" \
    --workspace-license "$quiche/COPYING" \
    --dest "$netty/license/quiche-deps" \
    --report "$out/licenses/crate-licenses.md"
cp "$netty/license/quiche-deps/INVENTORY.txt" "$out/licenses/INVENTORY.txt"

# --- Netty's build: classes and this platform's native.
m2="${MAVEN_REPO_LOCAL:-$work/m2}"
(cd "$netty" && ./mvnw -B -ntp -Dmaven.repo.local="$m2" -pl codec-classes-quic,codec-native-quic \
    -DskipTests package)

if [[ ! -d "$boring_cache" && -d "$native/target/boringssl" ]]; then
    cp -a "$native/target/boringssl" "$boring_cache"
fi

# --- Results in a Maven layout under QUICraft's own group, so nothing poses as upstream Netty.
classes_jar="$netty/codec-classes-quic/target/netty-codec-classes-quic-$NETTY_VERSION.jar"
classes_src="$netty/codec-classes-quic/target/netty-codec-classes-quic-$NETTY_VERSION-sources.jar"
native_jar="$native/target/netty-codec-native-quic-$NETTY_VERSION-$classifier.jar"
classes_dir="$out/repo/$GROUP_PATH/netty-codec-classes-quic/$QUICRAFT_VERSION"
native_dir="$out/repo/$GROUP_PATH/netty-codec-native-quic/$QUICRAFT_VERSION"
mkdir -p "$classes_dir" "$native_dir"
cp "$classes_jar" "$classes_dir/netty-codec-classes-quic-$QUICRAFT_VERSION.jar"
cp "$classes_src" "$classes_dir/netty-codec-classes-quic-$QUICRAFT_VERSION-sources.jar"
cp "$native_jar" "$native_dir/netty-codec-native-quic-$QUICRAFT_VERSION-$classifier.jar"

pom() { # artifactId, name, dependencies xml
    cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!-- Generated by QUICraft natives/build-netty-quic.sh. Netty $NETTY_TAG ($NETTY_COMMIT) with
     QUICraft's patches (natives/patches/), quiche $QUICHE_COMMIT, BoringSSL $BORINGSSL_COMMIT. -->
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>rs.sudoe.quicraft.netty</groupId>
  <artifactId>$1</artifactId>
  <version>$QUICRAFT_VERSION</version>
  <name>$2 (QUICraft build)</name>
  <licenses>
    <license>
      <name>Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0</url>
    </license>
  </licenses>
  <dependencies>$3
  </dependencies>
</project>
EOF
}
dep() {
    printf '\n    <dependency><groupId>io.netty</groupId><artifactId>%s</artifactId><version>%s</version></dependency>' \
        "$1" "$NETTY_VERSION"
}
pom netty-codec-classes-quic "Netty/Codec/Classes/Quic" \
    "$(dep netty-common)$(dep netty-buffer)$(dep netty-transport)$(dep netty-codec-base)$(dep netty-handler)" \
    > "$classes_dir/netty-codec-classes-quic-$QUICRAFT_VERSION.pom"
pom netty-codec-native-quic "Netty/Codec/Native/Quic" "" \
    > "$native_dir/netty-codec-native-quic-$QUICRAFT_VERSION.pom"

# --- Checks on the native.
check="$work/check-$classifier"
rm -rf "$check" && mkdir -p "$check"
(cd "$check" && unzip -q "$native_jar")
manifest="$check/META-INF/MANIFEST.MF"
grep -q "Quiche-Revision: $QUICHE_COMMIT" "$manifest"
grep -q "BoringSSL-Revision: $BORINGSSL_COMMIT" "$manifest"
lib="$(find "$check/META-INF/native" -type f -name '*netty_quiche42*')"
if [[ "${SKIP_PATCHES:-0}" != 1 ]]; then
    grep -q quiche_config_set_enable_relaxed_loss_threshold "$lib" \
        || { echo "native has no relaxed loss threshold binding" >&2; exit 1; }
fi
info="$out/build-info-$classifier.txt"
{
    echo "artifact: rs.sudoe.quicraft.netty:netty-codec-{classes,native}-quic:$QUICRAFT_VERSION ($classifier)"
    echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "netty: $NETTY_TAG $NETTY_COMMIT"
    echo "quiche: $QUICHE_COMMIT (features: $QUICHE_FEATURES)"
    echo "boringssl: $BORINGSSL_COMMIT"
    echo "patches applied: $([[ "${SKIP_PATCHES:-0}" == 1 ]] && echo none || echo yes)"
    for p in "$src"/patches/*/*.patch "$src"/quiche-Cargo.lock; do
        [[ -f "$p" ]] && echo "  $(sha256 "$p" | cut -d' ' -f1)  ${p#"$src"/}"
    done
    echo "rustc: $(rustc -V)"
    echo "cargo: $(cargo -V)"
    echo "cmake: $(cmake --version | head -1)"
    echo "cc: $(cc --version | head -1)"
    echo "java: $(java -version 2>&1 | head -1)"
    echo "outputs:"
    (cd "$out/repo" && find . -name '*.jar' -newer "$work/cargo-metadata.json" | sort | while read -r f; do
        echo "  $(sha256 "$f" | cut -d' ' -f1)  $f"
    done)
    if [[ "$os" == linux ]]; then
        echo "glibc floor: $(objdump -T "$lib" | grep -o 'GLIBC_[0-9.]*' | sort -uV | tail -1)"
        echo "needed: $(readelf -d "$lib" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
    fi
} > "$info"
cat "$info"
