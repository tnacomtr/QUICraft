# SPDX-License-Identifier: GPL-3.0-or-later
# Fabric dedicated server with the QUICraft mod, for the Phase 3 measurements: the bench client
# connects to it directly, without a proxy. The launcher is fetched at build time and checked
# against its sha256; nothing from Mojang or Fabric is committed.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

ARG MINECRAFT_VERSION=26.1.2
ARG FABRIC_LOADER=0.19.5
# Fabric's server launcher (fabric-installer, Apache-2.0); meta.fabricmc.net builds it the same
# way every time, so its checksum is stable.
ARG FABRIC_INSTALLER=1.1.2
ARG LAUNCHER_SHA256=a2bff0ba7c3aa718d6ab69e253c9cedeb02bc4ddbc4a817620f2485e0a78f364

WORKDIR /srv
ADD --checksum=sha256:${LAUNCHER_SHA256} \
    https://meta.fabricmc.net/v2/versions/loader/${MINECRAFT_VERSION}/${FABRIC_LOADER}/${FABRIC_INSTALLER}/server/jar \
    /srv/fabric-server-launch.jar
# Mojang EULA (https://aka.ms/MinecraftEULA): accepted for the testkit by the project
# owner on 2026-10-09.
RUN echo "eula=true" > eula.txt
# The launcher downloads the Mojang server jar and Fabric's libraries on first start. Do it once,
# here (--initSettings writes the default config and exits), so container start stays offline.
RUN java -jar fabric-server-launch.jar --initSettings --nogui && rm -rf logs
COPY server.properties /srv/server.properties
# Pre-generate the world (vanilla /forceload, no extra tools): 3x3 tiles of 16x16 chunks, i.e.
# 384 blocks each way from spawn, more than view distance 8 plus the spawn offset needs.
ARG PREGEN_TILES=3
COPY pregen.sh /usr/local/bin/pregen.sh
RUN pregen.sh "${PREGEN_TILES}" && rm -rf logs
COPY --from=quicraft quicraft-fabric.jar /srv/quicraft/quicraft-fabric.jar
COPY entrypoint.sh /usr/local/bin/fabric-entrypoint.sh

EXPOSE 25565 25565/udp
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/fabric-entrypoint.sh"]
