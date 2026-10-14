// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The production redirect rule (docs/updater.md 12.15): a hop is followed only to the release host's
 * own origin host or to one of an explicit set of CDN host names, and never to anything else. One
 * policy instance serves every host: the release host of the fetch decides which origin host is its
 * own, so another configured host's origin is only reachable when it is named in the allowlist,
 * which production never does.
 *
 * <p>A target is accepted only when it is absolute, its scheme is {@code https}, it has no userinfo,
 * no fragment, and no port other than the HTTPS default. Its host must be lowercase-equal to the
 * release host's origin host or to one allowlist name, and it must not be an IP literal (IPv4, or
 * IPv6 in brackets). The comparison is exact, so look-alike names such as
 * {@code release-assets.githubusercontent.com.evil.com} or
 * {@code evil-release-assets.githubusercontent.com} are refused.
 *
 * <p>HTTPS on every hop is enforced by the fetcher as well, not only here.
 */
public final class AllowlistRedirectPolicy implements RedirectPolicy {

    private static final int HTTPS_DEFAULT_PORT = 443;

    private final Set<String> cdnHosts;

    AllowlistRedirectPolicy(Set<String> cdnHosts) {
        Objects.requireNonNull(cdnHosts, "cdnHosts");
        Set<String> normalized = new HashSet<>();
        for (String name : cdnHosts) {
            normalized.add(Objects.requireNonNull(name, "cdnHost").toLowerCase(Locale.ROOT));
        }
        this.cdnHosts = Set.copyOf(normalized);
    }

    @Override
    public boolean allows(ReleaseHost host, URI from, URI to) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(to, "to");
        if (!to.isAbsolute() || to.isOpaque() || to.getRawAuthority() == null) {
            return false;
        }
        if (!"https".equalsIgnoreCase(to.getScheme())) {
            return false;
        }
        if (to.getRawAuthority().indexOf('@') >= 0 || to.getRawFragment() != null) {
            return false;
        }
        int port = to.getPort();
        if (port != -1 && port != HTTPS_DEFAULT_PORT) {
            return false;
        }
        String target = to.getHost();
        if (target == null) {
            return false;
        }
        target = target.toLowerCase(Locale.ROOT);
        if (isIpLiteral(target)) {
            return false;
        }
        return target.equals(host.origin().getHost()) || cdnHosts.contains(target);
    }

    /** True for an IPv4 literal, a bracketed IPv6 literal, or any other digits-and-dots-only host. */
    private static boolean isIpLiteral(String host) {
        if (host.isEmpty() || host.charAt(0) == '[' || host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }
}
