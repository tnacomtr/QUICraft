// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the three release hosts (docs/updater.md 12.14). Production builds have no origins yet
 * (blocker 5), so with no dev override the result is empty and checks and updates stay off.
 *
 * <p>The dev override is the system property {@code quicraft.updater.devOrigins}, for example
 * {@code modrinth=https://localhost:P1,github=https://localhost:P2,sudoe=https://localhost:P3}. It is
 * accepted only when all three keys appear once each and every origin's host is exactly
 * {@code localhost}. Anything else is ignored with one WARN.
 */
public final class UpdaterOrigins {

    /** The system property read by the facade. */
    public static final String PROPERTY = "quicraft.updater.devOrigins";

    static final String NOT_CONFIGURED = "release hosts not configured in this build";
    static final String DEV_IN_USE = "dev release origins are in use; this build does not check the real release hosts";

    private static final List<String> KEYS = List.of("modrinth", "github", "sudoe");

    private UpdaterOrigins() {
    }

    /**
     * Resolves the origins.
     *
     * @param devOrigins the property value, or {@code null} when the property is not set
     * @param log receives the INFO or WARN line
     * @return the hosts in the order modrinth, github, sudoe; empty when checks cannot run
     */
    public static Optional<ReleaseHosts> resolve(String devOrigins, UpdaterLog log) {
        Objects.requireNonNull(log, "log");
        if (devOrigins == null) {
            log.info(NOT_CONFIGURED);
            return Optional.empty();
        }
        try {
            ReleaseHosts hosts = parse(devOrigins);
            log.warn(DEV_IN_USE);
            return Optional.of(hosts);
        } catch (IllegalArgumentException e) {
            log.warn("dev release origins ignored: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static ReleaseHosts parse(String text) {
        Map<String, URI> byKey = new LinkedHashMap<>();
        for (String part : text.split(",", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("malformed entry");
            }
            String key = part.substring(0, eq).trim();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown host key");
            }
            if (byKey.containsKey(key)) {
                throw new IllegalArgumentException("duplicate host key");
            }
            URI origin;
            try {
                origin = URI.create(part.substring(eq + 1).trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("malformed origin");
            }
            if (!"localhost".equals(origin.getHost())) {
                throw new IllegalArgumentException("origin host is not localhost");
            }
            byKey.put(key, origin);
        }
        if (byKey.size() != KEYS.size()) {
            throw new IllegalArgumentException("a host key is missing");
        }
        List<ReleaseHost> hosts = new ArrayList<>();
        for (String key : KEYS) {
            try {
                hosts.add(new ReleaseHost(key, byKey.get(key)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("malformed origin");
            }
        }
        return ReleaseHosts.of(hosts);
    }
}
