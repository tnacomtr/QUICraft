// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;

/**
 * Decides whether a redirect hop is followed.
 *
 * <p>HTTPS on every hop is enforced by the fetcher, not by this policy. A redirect never changes
 * host identity: the hop keeps the original {@link ReleaseHost} and never adds a coverage vote.
 * The fetcher follows at most {@link #MAX_HOPS} redirects.
 */
public interface RedirectPolicy {

    /** The maximum number of redirect hops a fetch may follow. */
    int MAX_HOPS = 3;

    /**
     * Decides one hop.
     *
     * @param host the release host the fetch belongs to (unchanged by the redirect)
     * @param from the URI that answered with the redirect
     * @param to the redirect target
     * @return {@code true} to follow the hop
     */
    boolean allows(ReleaseHost host, URI from, URI to);

    /**
     * @return a policy that refuses every redirect
     */
    static RedirectPolicy denyAll() {
        return (host, from, to) -> false;
    }
}
