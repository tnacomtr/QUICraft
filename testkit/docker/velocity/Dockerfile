# SPDX-License-Identifier: GPL-3.0-or-later
# Velocity proxy for the testkit. The jar is fetched at build time and checked against its sha256.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

ARG VELOCITY_VERSION=4.2.0
ARG VELOCITY_BUILD=30
ARG VELOCITY_SHA256=35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8

WORKDIR /srv
ADD --checksum=sha256:${VELOCITY_SHA256} \
    https://fill-data.papermc.io/v1/objects/${VELOCITY_SHA256}/velocity-${VELOCITY_VERSION}-${VELOCITY_BUILD}.jar \
    /srv/velocity.jar
COPY velocity.toml /srv/velocity.toml.template
COPY entrypoint.sh /usr/local/bin/velocity-entrypoint.sh

EXPOSE 25565
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/velocity-entrypoint.sh"]
