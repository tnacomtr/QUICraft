// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the release setup of this build: the hosts, the endpoint layout and the redirect policy
 * of each host key (docs/updater.md 12.15).
 *
 * <p>With no dev override the production setup is returned: the GitHub host
 * {@code https://github.com/tnacomtr/QUICraft}, the Forgejo host
 * {@code https://git.sudoe.rs/tna/QUICraft}, the {@link ProductionReleaseEndpoints} layout, and the
 * redirect allowlist of each host ({@link AllowlistRedirectPolicy}; GitHub answers release downloads
 * with a redirect to its release asset CDN, Forgejo answers directly).
 *
 * <p>The dev override is the system property {@code quicraft.updater.devOrigins}, for example
 * {@code modrinth=https://localhost:P1,github=https://localhost:P2,sudoe=https://localhost:P3}. It is
 * accepted only when all three keys appear once each and every origin's host is exactly
 * {@code localhost}. Anything else is ignored with one WARN and checks stay off; an invalid value
 * never falls back to the production hosts silently.
 */
public final class UpdaterOrigins {

    /** The system property read by the facade. */
    public static final String PROPERTY = "quicraft.updater.devOrigins";

    /** The reason a runtime without hosts reports; only an invalid dev override reaches it now. */
    static final String NOT_CONFIGURED = "release hosts not configured in this build";
    static final String DEV_IN_USE = "dev release origins are in use; this build does not check the real release hosts";
    static final String PRODUCTION_IN_USE = "release hosts: github.com, git.sudoe.rs";

    private static final List<String> KEYS = List.of("modrinth", "github", "sudoe");

    /** The production origin of each compiled-in host. */
    private static final URI GITHUB_ORIGIN = URI.create("https://github.com");
    private static final URI SUDOE_ORIGIN = URI.create("https://git.sudoe.rs");

    /** The release asset CDN names GitHub release downloads redirect to. */
    private static final Set<String> GITHUB_CDN_HOSTS = Set.of(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com");

    private UpdaterOrigins() {
    }

    /**
     * Resolves the setup of this build.
     *
     * @param devOrigins the property value, or {@code null} when the property is not set
     * @param log receives the INFO or WARN line
     * @return the production setup when the property is unset, the dev setup when it is valid, and
     *     empty when it is set and invalid
     */
    public static Optional<ReleaseSetup> resolve(String devOrigins, UpdaterLog log) {
        Objects.requireNonNull(log, "log");
        if (devOrigins == null) {
            log.info(PRODUCTION_IN_USE);
            return Optional.of(production());
        }
        try {
            ReleaseHosts hosts = parse(devOrigins);
            log.warn(DEV_IN_USE);
            return Optional.of(new ReleaseSetup(hosts, new DevReleaseEndpoints(), Map.of()));
        } catch (IllegalArgumentException e) {
            log.warn("dev release origins ignored: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The production setup: the GitHub and git.sudoe.rs hosts and their endpoint layouts. Both hosts
     * serve the same four metadata files from their {@code updater-metadata} release.
     *
     * @return the production setup
     */
    static ReleaseSetup production() {
        ReleaseHosts hosts = ReleaseHosts.of(List.of(
                new ReleaseHost("github", GITHUB_ORIGIN),
                new ReleaseHost("sudoe", SUDOE_ORIGIN)));
        Map<String, RedirectPolicy> redirects = Map.of(
                "github", RedirectPolicy.allowlist(GITHUB_CDN_HOSTS),
                "sudoe", RedirectPolicy.allowlist(Set.of()));
        return new ReleaseSetup(hosts, new ProductionReleaseEndpoints(), redirects);
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
