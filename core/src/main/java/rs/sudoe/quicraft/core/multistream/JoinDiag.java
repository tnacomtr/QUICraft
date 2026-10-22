// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Off-wire timestamp marks of one {@code /3} join, for finding where its time goes without changing
 * anything on the wire. Diagnostics only: it never sends, holds back, reorders or fails anything.
 *
 * <p>Enabled with {@code -Dquicraft.v3.joindiag=true}; off by default. When it is off,
 * {@link #create(Side)} returns {@code null}, the platform keeps that {@code null} in one field, and
 * every {@link #mark(JoinDiag, String)} call is a single null test that allocates nothing. When it is
 * on, one instance per connection per side records {@link System#nanoTime()} marks in arrival order
 * and {@link #finish(JoinDiag)} writes exactly one line:
 * {@code QUICraft v3 join phases (server|client): name=ms-since-first-mark ...}.</p>
 *
 * <p>A throwing clock or logger never reaches the caller: recording stops for that connection and
 * everything else carries on as without diagnostics (the same rule as {@code StallDiag}). Marks are
 * written from the connection's event loop and, on a server, from the game's main thread, so
 * recording and formatting are synchronized.</p>
 */
public final class JoinDiag {
    /** Which endpoint of the join the marks belong to. */
    public enum Side {
        SERVER,
        CLIENT;

        String label() {
            return this == SERVER ? "server" : "client";
        }
    }

    /** The switch for every join's marks, read once per JVM. */
    public static final String PROPERTY = "quicraft.v3.joindiag";
    /** Whether the join marks are switched on for this JVM. */
    public static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private static final AttributeKey<JoinDiag> KEY = AttributeKey.valueOf("quicraft.joindiag");
    private static final Logger LOG = Logger.getLogger("QUICraft");

    private final Side side;
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private final List<String> names = new ArrayList<String>(16);
    private final List<Long> elapsed = new ArrayList<Long>(16);
    private long firstNanos;
    private boolean logged;
    /** Set by any recording or logging failure: this connection then stays silent. */
    private boolean broken;

    JoinDiag(Side side, LongSupplier clock, Consumer<String> sink) {
        this.side = side;
        this.clock = clock;
        this.sink = sink;
    }

    /**
     * One diagnostics instance for a connection of this side, or {@code null} when the property is
     * off. Never throws.
     */
    public static JoinDiag create(Side side) {
        return create(side, ENABLED, System::nanoTime, JoinDiag::log);
    }

    /** The enabled switch and the sinks are parameters here so tests can drive both. */
    static JoinDiag create(Side side, boolean enabled, LongSupplier clock, Consumer<String> sink) {
        if (!enabled || side == null || clock == null || sink == null) {
            return null;
        }
        return new JoinDiag(side, clock, sink);
    }

    /** Ties one connection's marks to its Netty channel, so the stream and the platform find them. */
    public static void attach(Channel channel, JoinDiag diag) {
        if (channel == null || diag == null) {
            return;
        }
        try {
            channel.attr(KEY).set(diag);
        } catch (Throwable diagnostic) {
            // No marks for this connection; the join itself is unaffected.
        }
    }

    /** The marks attached to a channel, or {@code null} when they are off or were never attached. */
    public static JoinDiag of(Channel channel) {
        if (channel == null) {
            return null;
        }
        try {
            return channel.attr(KEY).get();
        } catch (Throwable diagnostic) {
            return null;
        }
    }

    /** Records one mark; a null instance (diagnostics off) is one branch. Never throws. */
    public static void mark(JoinDiag diag, String name) {
        if (diag == null) {
            return;
        }
        try {
            diag.record(name);
        } catch (Throwable diagnostic) {
            // The marks stay out of the join's way.
        }
    }

    /** Writes this connection's one line, once; later marks and calls do nothing. Never throws. */
    public static void finish(JoinDiag diag) {
        if (diag == null) {
            return;
        }
        try {
            diag.emit();
        } catch (Throwable diagnostic) {
            // Logging is best effort.
        }
    }

    private void record(String name) {
        if (name == null) {
            return;
        }
        long now;
        try {
            now = clock.getAsLong();
        } catch (Throwable diagnostic) {
            broken = true;
            return;
        }
        synchronized (this) {
            if (broken || logged) {
                return;
            }
            if (names.isEmpty()) {
                firstNanos = now;
            }
            names.add(name);
            elapsed.add(Long.valueOf(now - firstNanos));
        }
    }

    private void emit() {
        List<String> markNames;
        List<Long> markTimes;
        synchronized (this) {
            if (logged) {
                return;
            }
            logged = true;
            if (broken) {
                return;
            }
            markNames = new ArrayList<String>(names);
            markTimes = new ArrayList<Long>(elapsed);
        }
        StringBuilder line = new StringBuilder(64 + 16 * markNames.size());
        line.append("QUICraft v3 join phases (").append(side.label()).append("):");
        for (int i = 0; i < markNames.size(); i++) {
            line.append(' ').append(markNames.get(i)).append('=').append(markTimes.get(i) / 1_000_000L);
        }
        try {
            sink.accept(line.toString());
        } catch (Throwable diagnostic) {
            broken = true;
        }
    }

    private static void log(String line) {
        try {
            LOG.log(Level.INFO, line);
        } catch (Throwable diagnostic) {
            // A logger that throws must not reach the join either.
        }
    }

    /** The side these marks describe, for tests. */
    Side side() {
        return side;
    }

    /** Marks recorded so far, for tests. */
    synchronized int markCount() {
        return names.size();
    }
}
