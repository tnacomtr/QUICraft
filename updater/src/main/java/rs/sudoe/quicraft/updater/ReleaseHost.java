// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One release host: a stable key ({@code modrinth}, {@code github} or {@code sudoe}) and its HTTPS
 * origin.
 *
 * <p>The origin is normalized on construction: scheme and host are lowercased, port 443 is dropped
 * (any other explicit port is kept), and the path is empty. Userinfo, a path other than empty or
 * {@code /}, a query and a fragment are rejected. Rejections raise {@link IllegalArgumentException}
 * with a fixed message that never echoes the URI.
 */
public record ReleaseHost(String key, URI origin) {

    private static final Set<String> KEYS = Set.of("modrinth", "github", "sudoe");
    private static final int HTTPS_DEFAULT_PORT = 443;

    public ReleaseHost {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(origin, "origin");
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("unknown release host key");
        }
        origin = normalize(origin);
    }

    private static URI normalize(URI uri) {
        if (!uri.isAbsolute() || uri.isOpaque()) {
            throw new IllegalArgumentException("invalid release host origin");
        }
        if (!"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("release host origin must be https");
        }
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.indexOf('@') >= 0 || rawAuthority.endsWith(":")) {
            throw new IllegalArgumentException("invalid release host origin");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("invalid release host origin");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty() && !"/".equals(rawPath)) {
            throw new IllegalArgumentException("release host origin must not have a path");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("release host origin must not have a query or fragment");
        }
        int port = uri.getPort();
        if (port == HTTPS_DEFAULT_PORT) {
            port = -1;
        } else if (port != -1 && (port < 1 || port > 65_535)) {
            throw new IllegalArgumentException("invalid release host port");
        }
        String text = "https://" + host.toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port);
        return URI.create(text);
    }
}
