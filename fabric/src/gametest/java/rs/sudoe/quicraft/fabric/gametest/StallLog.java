// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Counts the lines {@code StallDiag} writes to the {@code QUICraft} JUL logger while an arm sample
 * runs, so a row can say whether the /3 session reported a stall. The watchdog stays internal to
 * core (its sink is the production logger), so the count comes from a handler on that same logger:
 * every line starts with {@code QUICraft v3 stall:}. A JUL line is one event, detail lines included.
 *
 * <p>The handler is installed once per JVM on first use and never throws; a failure to count can
 * only lower a diagnostic number, never change a run. {@link #reset()} is called at the start of a
 * measured run: the harness may run several tests in one client JVM.
 */
final class StallLog {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    private static final String PREFIX = "QUICraft v3 stall:";
    private static final AtomicInteger EVENTS = new AtomicInteger();

    private StallLog() {}

    /** Installs the handler (first call) and starts counting from zero. */
    static synchronized void reset() {
        install();
        EVENTS.set(0);
    }

    /** Stall lines logged since the last {@link #reset()}; installs the handler if needed. */
    static int events() {
        install();
        return EVENTS.get();
    }

    private static synchronized void install() {
        try {
            for (Handler handler : LOG.getHandlers()) {
                if (handler instanceof Counter) {
                    return;
                }
            }
            LOG.addHandler(new Counter());
        } catch (Throwable unavailable) {
            // Diagnostics only: without a JUL handler the row reports 0 events, never a failed run.
        }
    }

    private static final class Counter extends Handler {
        Counter() {
            setLevel(Level.ALL);
        }

        @Override
        public void publish(LogRecord record) {
            try {
                String message = record == null ? null : record.getMessage();
                if (message != null && message.startsWith(PREFIX)) {
                    EVENTS.incrementAndGet();
                }
            } catch (Throwable ignored) {
                // A counting failure must never break the caller's logging.
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }
}
