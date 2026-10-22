// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Stall watchdog for one /3 session. Diagnostics only: it never changes protocol behaviour.
 *
 * <p>Enabled with {@code -Dquicraft.v3.stalldiag=true}. When disabled, the session never creates an instance, so the
 * tick pays one null test. When enabled, a tick logs when no unit has been applied for {@link #STALL_NANOS} and either
 * the receiver holds a dependency-blocked head or the sender has queued records. Events are rate limited to one per
 * {@link #INTERVAL_NANOS}, and at most {@link #MAX_LINES} lines are written per connection (header and detail lines
 * both count). Every line is cut to {@link #MAX_LINE_CHARS}.
 *
 * <p>The clock is injected and must be the session's owner clock, so tests can drive time without sleeping.
 */
final class StallDiag {
    /** Whether the watchdog is switched on for this JVM. Read once. */
    static final boolean ENABLED = Boolean.getBoolean("quicraft.v3.stalldiag");
    /** No applied unit for this long, measured from the last apply or from session creation. */
    static final long STALL_NANOS = 3_000_000_000L;
    /** Minimum time between two logged events. */
    static final long INTERVAL_NANOS = 2_000_000_000L;
    /** Lines written per connection, header and detail lines included. */
    static final int MAX_LINES = 20;
    /** Longest line written; longer lines are cut and end with a marker. */
    static final int MAX_LINE_CHARS = 1200;

    private static final String PREFIX = "QUICraft v3 stall: side=";

    private final LongSupplier clock;
    private final Consumer<String> sink;
    private long lastProgressNanos;
    private long lastEventNanos;
    private boolean eventLogged;
    private int lines;
    /** Set after any failure of the clock, the detail supplier or the sink; the watchdog then stays silent. */
    private boolean disabled;

    /**
     * @param clock the session's owner clock, in nanoseconds
     * @param sink receives each formatted line (production: the QUICraft logger at INFO)
     */
    StallDiag(LongSupplier clock, Consumer<String> sink) {
        this.clock = clock;
        this.sink = sink;
        try {
            this.lastProgressNanos = clock.getAsLong();
        } catch (Throwable diagnostic) {
            disabled = true;
        }
    }

    /** Records that a unit was applied (committed) now. Cheap: one clock read. Never throws. */
    void progress() {
        if (disabled) {
            return;
        }
        try {
            lastProgressNanos = clock.getAsLong();
        } catch (Throwable diagnostic) {
            disabled = true;
        }
    }

    /**
     * Called from the session's periodic tick. {@code details} is only invoked when an event is actually logged, so
     * the detail strings are built on the rare path. Never throws: any failure of the clock, the detail supplier or
     * the sink disables the watchdog for this connection, and the session carries on exactly as without diagnostics.
     *
     * @param side {@code client} or {@code server}
     * @param receiverBlocked the receiver holds a head that is not yet enqueued (dependency or slot blocked)
     * @param senderQueued the sender has records queued and not yet written
     * @param details the detail lines (per stream and connection level), without the prefix
     */
    void tick(String side, boolean receiverBlocked, boolean senderQueued, Supplier<List<String>> details) {
        if (disabled) {
            return;
        }
        try {
            long now = clock.getAsLong();
            if (!due(now, receiverBlocked, senderQueued)) {
                return;
            }
            eventLogged = true;
            lastEventNanos = now;
            long idleMillis = (now - lastProgressNanos) / 1_000_000L;
            emit(PREFIX + side + " idleMs=" + idleMillis + " receiverBlocked=" + receiverBlocked
                    + " senderQueued=" + senderQueued);
            List<String> extra = details.get();
            for (int i = 0; i < extra.size() && lines < MAX_LINES; i++) {
                emit(PREFIX + side + " " + extra.get(i));
            }
        } catch (Throwable diagnostic) {
            disabled = true;
        }
    }

    /** Stops the watchdog for this connection; used when the caller's own diagnostic reads fail. */
    void disable() {
        disabled = true;
    }

    /** Whether a stall event should be logged at {@code now}. Package-private for tests. */
    boolean due(long now, boolean receiverBlocked, boolean senderQueued) {
        if (lines >= MAX_LINES) {
            return false;
        }
        if (now - lastProgressNanos < STALL_NANOS) {
            return false;
        }
        if (!receiverBlocked && !senderQueued) {
            return false;
        }
        return !eventLogged || now - lastEventNanos >= INTERVAL_NANOS;
    }

    /** Number of lines written so far. Package-private for tests. */
    int linesWritten() {
        return lines;
    }

    private void emit(String line) {
        if (lines >= MAX_LINES) {
            return;
        }
        lines++;
        sink.accept(truncate(line));
    }

    static String truncate(String line) {
        if (line.length() <= MAX_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_LINE_CHARS - 3) + "...";
    }
}
