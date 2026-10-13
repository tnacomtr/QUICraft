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
 * <p>The host must be a plain LDH DNS name: at least two labels, each 1..63 characters of
 * {@code [a-z0-9-]} after lowercasing, with no leading or trailing hyphen; total length at most 253;
 * no empty label, no trailing dot; the last label is not all digits. IP literals, IPv6 brackets,
 * zone identifiers and underscores are rejected. The single label {@code localhost} (exactly, after
 * lowercasing) is the one exception to the two-label rule, for local test fixtures and local mirrors.
 *
 * <p>The origin is normalized on construction: scheme and host are lowercased, port 443 is dropped
 * (any other explicit port is kept), and the path is empty. Userinfo, a path other than empty or
 * {@code /}, a query and a fragment are rejected. Rejections raise {@link IllegalArgumentException}
 * with a fixed message that never echoes the URI.
 */
public record ReleaseHost(String key, URI origin) {

    private static final Set<String> KEYS = Set.of("modrinth", "github", "sudoe");
    private static final int HTTPS_DEFAULT_PORT = 443;
    private static final int MAX_NAME_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final String LOCALHOST = "localhost";

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
        if (host == null) {
            throw new IllegalArgumentException("invalid release host origin");
        }
        host = host.toLowerCase(Locale.ROOT);
        requireLdhName(host);
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
        String text = "https://" + host + (port == -1 ? "" : ":" + port);
        return URI.create(text);
    }

    /** Requires a plain LDH DNS name (already lowercased). */
    private static void requireLdhName(String host) {
        if (LOCALHOST.equals(host)) {
            return;
        }
        if (host.isEmpty() || host.length() > MAX_NAME_LENGTH || host.endsWith(".")) {
            throw new IllegalArgumentException("invalid release host name");
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("invalid release host name");
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                throw new IllegalArgumentException("invalid release host name");
            }
            if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw new IllegalArgumentException("invalid release host name");
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
                if (!ok) {
                    throw new IllegalArgumentException("invalid release host name");
                }
            }
        }
        if (isAllDigits(labels[labels.length - 1])) {
            throw new IllegalArgumentException("invalid release host name");
        }
    }

    private static boolean isAllDigits(String label) {
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
