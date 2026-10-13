// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Duration;
import java.util.Objects;

/**
 * The three fetch timeouts used for metadata collection. Each value must be positive and within the
 * limits {@link FetchRequest} enforces, so a valid instance always yields a valid request.
 */
public record FetchTimeouts(Duration connect, Duration idle, Duration overall) {

    private static final Duration DEFAULT_CONNECT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_IDLE = Duration.ofSeconds(15);
    private static final Duration DEFAULT_OVERALL = Duration.ofSeconds(60);

    public FetchTimeouts {
        Objects.requireNonNull(connect, "connect");
        Objects.requireNonNull(idle, "idle");
        Objects.requireNonNull(overall, "overall");
        requireWithin(connect, FetchRequest.MAX_CONNECT, "connect");
        requireWithin(idle, FetchRequest.MAX_IDLE, "idle");
        requireWithin(overall, FetchRequest.MAX_DEADLINE, "overall");
    }

    /** @return the default timeouts: 10 s connect, 15 s idle read, 60 s overall */
    public static FetchTimeouts defaults() {
        return new FetchTimeouts(DEFAULT_CONNECT, DEFAULT_IDLE, DEFAULT_OVERALL);
    }

    private static void requireWithin(Duration d, Duration max, String field) {
        if (d.isZero() || d.isNegative() || d.compareTo(max) > 0) {
            throw new IllegalArgumentException("invalid " + field + " timeout");
        }
    }
}
