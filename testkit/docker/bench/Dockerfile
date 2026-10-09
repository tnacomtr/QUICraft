# SPDX-License-Identifier: GPL-3.0-or-later
# Headless bench client and mock session server. Build context is testkit/, after
# `./gradlew :testkit:installDist`.
FROM eclipse-temurin:25-jre@sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636

# iproute2 provides tc/ip for the netem profiles.
RUN apt-get update \
    && apt-get install -y --no-install-recommends iproute2 \
    && rm -rf /var/lib/apt/lists/*
COPY build/install/quicraft-testkit /opt/testkit
COPY docker/bench/entrypoint.sh /usr/local/bin/bench-entrypoint.sh
COPY netem/profiles.sh /opt/testkit/netem-profiles.sh

ENTRYPOINT ["/usr/local/bin/bench-entrypoint.sh"]
