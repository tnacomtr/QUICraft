# SPDX-License-Identifier: GPL-3.0-or-later
# Paper backend for the testkit. The jar is fetched at build time and checked against its
# sha256; nothing from Mojang or Paper is committed.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

ARG PAPER_VERSION=26.1.2
ARG PAPER_BUILD=74
ARG PAPER_SHA256=1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7

WORKDIR /srv
ADD --checksum=sha256:${PAPER_SHA256} \
    https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar \
    /srv/paper.jar
# Paperclip downloads the Mojang server jar and applies Paper's patches. Do it once, here,
# so the layer is cached and container start doesn't hit the network.
RUN java -Dpaperclip.patchonly=true -jar paper.jar

# Mojang EULA (https://aka.ms/MinecraftEULA): accepted for the testkit by the project
# owner on 2026-10-09.
RUN echo "eula=true" > eula.txt
COPY server.properties /srv/server.properties
COPY config/paper-global.yml /srv/config/paper-global.yml

# Pre-generate the world at build time (Chunky 1.5.3, GPL-3.0-only, removed again afterwards).
# 256 blocks covers view distance 8 around spawn with margin for the spawn offset.
ARG CHUNKY_SHA256=530d2c7430a96a39957391b7088be144daa3108f7665896d1c23aa8dd4af32f3
ARG PREGEN_RADIUS=256
ADD --checksum=sha256:${CHUNKY_SHA256} \
    https://cdn.modrinth.com/data/fALzjamp/versions/MdY6JATr/Chunky-Bukkit-1.5.3.jar \
    /srv/plugins/Chunky.jar
COPY pregen.sh /usr/local/bin/pregen.sh
RUN pregen.sh "${PREGEN_RADIUS}"

EXPOSE 25565
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-Xms2G", "-Xmx2G", "-jar", "paper.jar", "--nogui"]
