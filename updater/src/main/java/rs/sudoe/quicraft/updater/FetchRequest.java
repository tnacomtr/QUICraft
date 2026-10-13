// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * One fetch: the host it belongs to, the first URI, the byte cap, the timeouts and the redirect
 * policy.
 *
 * <p>The URI must be absolute HTTPS with no userinfo and no fragment, and its scheme, host and port
 * must equal the host's normalized origin. Redirect hops are handled inside the fetcher, never by new
 * requests. The overall deadline never resets across hops. Null components raise
 * {@link NullPointerException}; other rejections raise {@link IllegalArgumentException}.
 */
public record FetchRequest(
        ReleaseHost host,
        URI uri,
        long maxBytes,
        Duration connectTimeout,
        Duration idleReadTimeout,
        Duration overallDeadline,
        RedirectPolicy redirects) {

    /** Largest byte cap accepted by {@code download}: 2 GiB. */
    public static final long MAX_BYTES = 1L << 31;
    /**
     * Largest byte cap {@code fetch} accepts: 64 MiB. A larger request fails with
     * {@link FetchOutcome#OVERSIZE} without reading any body.
     */
    public static final long MAX_FETCH_BYTES = 64L << 20;
    /** Largest connect timeout: 10 minutes. */
    public static final Duration MAX_CONNECT = Duration.ofMinutes(10);
    /** Largest idle read timeout: 10 minutes. */
    public static final Duration MAX_IDLE = Duration.ofMinutes(10);
    /** Largest overall deadline: 1 hour. */
    public static final Duration MAX_DEADLINE = Duration.ofHours(1);

    private static final int HTTPS_DEFAULT_PORT = 443;

    public FetchRequest {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(idleReadTimeout, "idleReadTimeout");
        Objects.requireNonNull(overallDeadline, "overallDeadline");
        Objects.requireNonNull(redirects, "redirects");
        requireUriOfHost(uri, host);
        if (maxBytes < 1 || maxBytes > MAX_BYTES) {
            throw new IllegalArgumentException("invalid maxBytes");
        }
        requireWithin(connectTimeout, MAX_CONNECT, "connectTimeout");
        requireWithin(idleReadTimeout, MAX_IDLE, "idleReadTimeout");
        requireWithin(overallDeadline, MAX_DEADLINE, "overallDeadline");
    }

    private static void requireUriOfHost(URI uri, ReleaseHost host) {
        if (!uri.isAbsolute() || uri.isOpaque() || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("fetch uri must be absolute https");
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.indexOf('@') >= 0 || rawAuthority.endsWith(":")) {
            throw new IllegalArgumentException("fetch uri must not have userinfo");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("fetch uri must not have a fragment");
        }
        String uriHost = uri.getHost();
        if (uriHost == null || !uriHost.toLowerCase(Locale.ROOT).equals(host.origin().getHost())) {
            throw new IllegalArgumentException("fetch uri does not match the release host");
        }
        if (effectivePort(uri) != effectivePort(host.origin())) {
            throw new IllegalArgumentException("fetch uri does not match the release host");
        }
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        return port == -1 ? HTTPS_DEFAULT_PORT : port;
    }

    private static void requireWithin(Duration d, Duration max, String field) {
        if (d.isZero() || d.isNegative() || d.compareTo(max) > 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
