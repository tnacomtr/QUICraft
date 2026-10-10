# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build image for QUICraft's patched netty-codec-native-quic on Linux (x86_64 and aarch64; build
# on a native host of each). Used by natives/build-linux.sh; the build itself is
# natives/build-netty-quic.sh.
#
# AlmaLinux 8 (glibc 2.28) rather than Netty's CentOS 7 image: CentOS 7 is end of life. The result
# needs glibc 2.28 at most (upstream's needs 2.16); on older systems the native fails to load and
# QUICraft runs TCP-only (docs/protocol.md §11).
#
# Every tool not from the pinned base image's repositories is pinned by version and SHA-256.
FROM almalinux:8.10@sha256:8b469a3a78515e8a18ea8fc727e6a3679e1d0c5ba6f58d5b30be3d0d9b86cffe

# gcc-toolset-13: BoringSSL needs a newer C++ compiler than the system GCC 8. clang-devel: libclang
# for bindgen (boring-sys). patchelf (EPEL): Netty's pom drops the ld-linux DT_NEEDED with it.
RUN dnf -y install dnf-plugins-core epel-release \
 && dnf config-manager --set-enabled powertools \
 && dnf -y install \
      autoconf automake diffutils file findutils gcc-toolset-13-gcc gcc-toolset-13-gcc-c++ \
      gcc-toolset-13-libstdc++-devel git glibc-devel clang-devel libtool make patchelf perl \
      perl-IPC-Cmd tar unzip which xz \
 && dnf clean all \
 && patchelf --version

ARG CMAKE_VERSION=3.31.9
ARG NINJA_VERSION=1.12.1
ARG TEMURIN_VERSION=11.0.32.1_1
ARG TEMURIN_TAG=jdk-11.0.32.1%2B1
ARG RUSTUP_VERSION=1.29.1
ARG RUST_VERSION=1.98.0

RUN set -eux; \
    arch="$(uname -m)"; \
    case "$arch" in \
      x86_64) \
        cmake_sha=312d78e0b7c5c9b4a97a87a46c3e525aa84895fe8135c238c4616bba73dc9518; \
        ninja_zip=ninja-linux.zip; ninja_sha=6f98805688d19672bd699fbbfa2c2cf0fc054ac3df1f0e6a47664d963d530255; \
        jdk_arch=x64; jdk_sha=5c3f68887c325d36d852ba534303e1f5f1f5cae7d6cc1e951d73e0d8e98a058d; \
        rustup_sha=dda7234360b7f578ca8b0ddcb80145646fa61a67c1720a5abc7051b35c9fcb71 ;; \
      aarch64) \
        cmake_sha=09b7127b09e3d3ed5fa894f4e59f6a01112f267da6acc4249e46dba97b14afcb; \
        ninja_zip=ninja-linux-aarch64.zip; ninja_sha=5c25c6570b0155e95fce5918cb95f1ad9870df5768653afe128db822301a05a1; \
        jdk_arch=aarch64; jdk_sha=f27033e6f7523c1b0b2565a78e9c0e0abe5596a854ce00ca04ec1b06ece7a935; \
        rustup_sha=15f6e4ce9f583b929c996c91562bad6d4454f3281de858b02cdfdef615fac433 ;; \
      *) echo "unsupported architecture $arch" >&2; exit 1 ;; \
    esac; \
    cd /tmp; \
    curl -fsSLo cmake.tgz "https://github.com/Kitware/CMake/releases/download/v${CMAKE_VERSION}/cmake-${CMAKE_VERSION}-linux-${arch}.tar.gz"; \
    echo "$cmake_sha  cmake.tgz" | sha256sum -c -; \
    mkdir /opt/cmake && tar xzf cmake.tgz -C /opt/cmake --strip-components=1; \
    curl -fsSLo ninja.zip "https://github.com/ninja-build/ninja/releases/download/v${NINJA_VERSION}/${ninja_zip}"; \
    echo "$ninja_sha  ninja.zip" | sha256sum -c -; \
    unzip -q ninja.zip -d /usr/local/bin; \
    curl -fsSLo jdk.tgz "https://github.com/adoptium/temurin11-binaries/releases/download/${TEMURIN_TAG}/OpenJDK11U-jdk_${jdk_arch}_linux_hotspot_${TEMURIN_VERSION}.tar.gz"; \
    echo "$jdk_sha  jdk.tgz" | sha256sum -c -; \
    mkdir /opt/jdk && tar xzf jdk.tgz -C /opt/jdk --strip-components=1; \
    curl -fsSLo rustup-init "https://static.rust-lang.org/rustup/archive/${RUSTUP_VERSION}/${arch}-unknown-linux-gnu/rustup-init"; \
    echo "$rustup_sha  rustup-init" | sha256sum -c -; \
    chmod +x rustup-init; \
    RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo ./rustup-init -y --no-modify-path --profile minimal \
        --default-toolchain "$RUST_VERSION"; \
    chmod -R a+rX /opt/rustup /opt/cargo; \
    rm -rf /tmp/*

ENV JAVA_HOME=/opt/jdk \
    RUSTUP_HOME=/opt/rustup \
    PATH=/opt/rh/gcc-toolset-13/root/usr/bin:/opt/cargo/bin:/opt/cmake/bin:/opt/jdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LIBCLANG_PATH=/usr/lib64
