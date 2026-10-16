// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

/**
 * The configuration stage's hold on Finish Configuration (docs/protocol.md 12.4): a flag the
 * game's configuration task polls once per tick. It is open only when binding activated; a
 * failure leaves it closed for good, because the connection is being closed and nothing more of the
 * configuration may run on it.
 *
 * <p>Threading: written on the connection's event loop, polled on the game's main thread.</p>
 */
final class BindingHold {
    private volatile boolean released;
    private volatile boolean failed;

    /** Binding activated: the configuration stage may go on. */
    void release() {
        released = true;
    }

    /** Binding failed: the hold never opens. */
    void fail() {
        failed = true;
    }

    /** What the configuration task's {@code tick()} answers: true once the stage may go on. */
    boolean open() {
        return released && !failed;
    }

    boolean failed() {
        return failed;
    }
}
