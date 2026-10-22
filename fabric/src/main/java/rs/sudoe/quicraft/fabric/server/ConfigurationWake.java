// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Wakes the configuration stage as soon as a held task releases it, instead of waiting for the next
 * server tick (the join-phase fix of {@code p5-joindiag}): the released task is ticked and finished
 * once, on the server thread, exactly as {@code ServerConfigurationPacketListenerImpl.tick()} would
 * do on its next pass.
 *
 * <p>It is an optimisation only. The vanilla tick stays in charge and finishes the task itself if
 * this wake-up never runs, runs after it, or throws: every failure here is swallowed, and the
 * {@link Stage} checks on every call that the connection and the listener are still the ones that
 * were released.</p>
 *
 * <p>One instance wakes one released task at most once, whatever happens. The run may happen on the
 * server thread or, when the server refuses the task, never; either way the tick remains.</p>
 */
final class ConfigurationWake {
    /** The released configuration task, as the server thread sees it. */
    interface Stage {
        /** Whether the task may still be advanced: same connection, same listener, same task. */
        boolean open();

        /** Advances the stage exactly as the vanilla tick would. May throw; that is caught. */
        void advance();
    }

    private final Stage stage;
    private final Consumer<Runnable> serverThread;
    private final AtomicBoolean woken = new AtomicBoolean();
    private final AtomicBoolean ran = new AtomicBoolean();

    ConfigurationWake(Stage stage, Consumer<Runnable> serverThread) {
        this.stage = stage;
        this.serverThread = serverThread;
    }

    /**
     * Schedules the one wake-up on the server thread. Later calls do nothing. A server that refuses
     * the task leaves the vanilla tick as the only path, which is the same join one tick later.
     */
    void schedule() {
        if (!woken.compareAndSet(false, true)) {
            return;
        }
        try {
            serverThread.accept(this::run);
        } catch (Throwable refused) {
            // The normal tick still advances the stage.
        }
    }

    /** The scheduled task: advance once if the stage is still the released one. Never throws. */
    private void run() {
        if (!ran.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!stage.open()) {
                return;
            }
            stage.advance();
        } catch (Throwable failed) {
            // The normal tick still advances the stage; this wake-up only made it earlier.
        }
    }
}
