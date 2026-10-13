// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The configured release hosts: one to three entries with distinct keys and distinct normalized
 * origins. The list is copied defensively.
 */
public record ReleaseHosts(List<ReleaseHost> hosts) {

    private static final int MAX_HOSTS = 3;

    public ReleaseHosts {
        hosts = List.copyOf(hosts);
        if (hosts.isEmpty() || hosts.size() > MAX_HOSTS) {
            throw new IllegalArgumentException("invalid release host count");
        }
        Set<String> keys = new HashSet<>();
        Set<String> origins = new HashSet<>();
        for (ReleaseHost host : hosts) {
            if (!keys.add(host.key())) {
                throw new IllegalArgumentException("duplicate release host key");
            }
            if (!origins.add(host.origin().toString())) {
                throw new IllegalArgumentException("duplicate release host origin");
            }
        }
    }

    /**
     * Finds the configured host with the given key.
     *
     * @param key the host key; must not be null
     * @return the host, or empty when no host has that key
     */
    public Optional<ReleaseHost> byKey(String key) {
        Objects.requireNonNull(key, "key");
        for (ReleaseHost host : hosts) {
            if (host.key().equals(key)) {
                return Optional.of(host);
            }
        }
        return Optional.empty();
    }
}
