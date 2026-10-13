// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * One fetch: the host it belongs to, the first URI (absolute HTTPS), the byte cap, the timeouts and
 * the redirect policy.
 *
 * <p>The overall deadline never resets across redirect hops. Null components raise
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

    /** Largest accepted byte cap: 2 GiB. */
    static final long MAX_BYTES_LIMIT = 1L << 31;

    public FetchRequest {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(idleReadTimeout, "idleReadTimeout");
        Objects.requireNonNull(overallDeadline, "overallDeadline");
        Objects.requireNonNull(redirects, "redirects");
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getRawAuthority() == null
                || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("fetch uri must be absolute https");
        }
        if (maxBytes < 1 || maxBytes > MAX_BYTES_LIMIT) {
            throw new IllegalArgumentException("invalid maxBytes");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(idleReadTimeout, "idleReadTimeout");
        requirePositive(overallDeadline, "overallDeadline");
    }

    private static void requirePositive(Duration d, String field) {
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
