// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.Map;
import java.util.Objects;

/**
 * Everything one resolved release host set needs: the hosts, the endpoint layout, and the redirect
 * policy of each host key (docs/updater.md 12.15). A host key missing from {@code redirects} is
 * refused every hop, like {@link RedirectPolicy#denyAll()}.
 *
 * <p>Production and the dev override differ only in these three values, so the runtime takes them
 * together and no caller pairs one build's hosts with another build's endpoints.
 */
public record ReleaseSetup(
        ReleaseHosts hosts,
        ReleaseEndpoints endpoints,
        Map<String, RedirectPolicy> redirects) {

    public ReleaseSetup {
        Objects.requireNonNull(hosts, "hosts");
        Objects.requireNonNull(endpoints, "endpoints");
        redirects = Map.copyOf(Objects.requireNonNull(redirects, "redirects"));
    }
}
