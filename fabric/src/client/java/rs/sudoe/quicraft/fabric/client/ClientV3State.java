// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.pin.PinExpiry;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.fabric.QuicraftFabric;

/**
 * The client's multi-stream state files and the thread that touches them (docs/protocol.md 12.15,
 * 12.16): {@code pins-v2.json} and the re-bind state in the mod's config directory, one daemon
 * worker for every read and write of them (the stores fsync on write, so never the render thread or
 * a Netty loop), and the 30 day pin expiry at start and every hour after.
 *
 * <p>Only created once the player turned multi-stream on: with it off, no file is read or written
 * and no thread is started, exactly as before this setting existed.</p>
 */
final class ClientV3State {
    static final String PINS_FILE = "pins-v2.json";
    static final String REBIND_FILE = "rebind-v1.json";
    /** How often expired pins are purged while the game runs (12.15: also at startup). */
    static final long PURGE_PERIOD_MINUTES = 60;

    private final PinStore pins;
    private final RebindState rebinds;
    private final ExecutorService worker;
    private final ScheduledExecutorService timer;

    private ClientV3State(PinStore pins, RebindState rebinds, ExecutorService worker,
            ScheduledExecutorService timer) {
        this.pins = pins;
        this.rebinds = rebinds;
        this.worker = worker;
        this.timer = timer;
    }

    /** Loads both files from {@code dir} (config/quicraft) and queues the startup purge. */
    static ClientV3State open(Path dir, Clock clock) {
        PinStore pins = PinStore.load(dir.resolve(PINS_FILE), clock);
        RebindState rebinds = RebindState.load(dir.resolve(REBIND_FILE));
        ExecutorService worker = Executors.newSingleThreadExecutor(
                daemon("QUICraft-pins"));
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("QUICraft-pin-expiry"));
        ClientV3State state = new ClientV3State(pins, rebinds, worker, timer);
        state.purgeLater();
        timer.scheduleWithFixedDelay(state::purgeLater, PURGE_PERIOD_MINUTES, PURGE_PERIOD_MINUTES,
                TimeUnit.MINUTES);
        return state;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Whether this join takes the multi-stream connect path at all. Anything but a player who turned
     * the setting on, with the state files open and a logical name to key pins by, takes the path
     * that existed before multi-stream, untouched (docs/protocol.md 12.1).
     */
    static boolean useMultistream(boolean setting, boolean stateOpen, String logicalHost, int logicalPort) {
        return setting && stateOpen && logicalHost != null && !logicalHost.isEmpty()
                && logicalPort > 0 && logicalPort <= 65535;
    }

    PinStore pins() {
        return pins;
    }

    RebindState rebinds() {
        return rebinds;
    }

    /** The one thread that touches the files; the connector wraps it so its tasks stay in order. */
    ExecutorService worker() {
        return worker;
    }

    /** Removes the pins past their 30 days, with their re-bind state, on the worker (12.15). */
    void purgeLater() {
        try {
            worker.execute(this::purge);
        } catch (RuntimeException e) {
            // A worker that is gone means the game is closing.
        }
    }

    /** Worker only. Never throws: expiry failing must not stop anything else. */
    void purge() {
        try {
            PinExpiry.purge(pins, rebinds, rebinds.activeKeys());
        } catch (Throwable t) {
            QuicraftFabric.LOG.warn("QUICraft: purging expired pins failed: {}", t.toString());
        }
    }

    /** Tests: runs the pending worker tasks and stops both threads. */
    void close() throws InterruptedException {
        timer.shutdownNow();
        worker.shutdown();
        worker.awaitTermination(10, TimeUnit.SECONDS);
    }
}
