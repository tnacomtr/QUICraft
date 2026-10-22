// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.multistream.JoinDiag;

/**
 * The join marks of the connection whose configuration exchange is running on this thread, so a
 * static game helper with no connection of its own ({@code RegistrySynchronization.packRegistries},
 * {@code TagNetworkSerialization.serializeTagsToNetwork}) can mark its own start and end.
 *
 * <p>The scope is opened only around one {@code /3} join's {@code handleResponse} call, on the
 * server thread, so a TCP join or a join whose diagnostics are off finds no instance and records
 * nothing. Off means one empty {@link ThreadLocal#get()} per call, and {@link #mark} then does
 * nothing.</p>
 */
public final class JoinDiagScope {
    private static final ThreadLocal<JoinDiag> CURRENT = new ThreadLocal<JoinDiag>();

    private JoinDiagScope() {}

    /** The marks of the connection being served on this thread, or null. Never throws. */
    public static @Nullable JoinDiag current() {
        try {
            return CURRENT.get();
        } catch (Throwable broken) {
            return null;
        }
    }

    /** Records one mark on the current connection's marks, if any. Never throws. */
    public static void mark(String name) {
        JoinDiag.mark(current(), name);
    }

    /** Makes {@code diag} current for this thread until {@link Scope#close()}. Never throws. */
    public static Scope open(@Nullable JoinDiag diag) {
        if (diag == null) {
            return Scope.NONE;
        }
        JoinDiag previous;
        try {
            previous = CURRENT.get();
            CURRENT.set(diag);
        } catch (Throwable broken) {
            return Scope.NONE;
        }
        return new Scope(previous);
    }

    /** One open scope; closing restores whatever was current before it. */
    public static class Scope {
        static final Scope NONE = new Scope(null) {
            @Override
            public void close() {
                // Nothing was opened.
            }
        };

        private final JoinDiag previous;
        private boolean closed;

        Scope(@Nullable JoinDiag previous) {
            this.previous = previous;
        }

        /** Restores the previous marks. Idempotent, and never throws. */
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            } catch (Throwable broken) {
                // A failed clean-up must not fail the join's configuration exchange.
            }
        }
    }
}
