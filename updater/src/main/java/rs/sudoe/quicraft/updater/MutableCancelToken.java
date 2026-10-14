// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** A {@link CancelToken} that the owner of an operation can cancel from any thread. */
public final class MutableCancelToken implements CancelToken {
    private volatile boolean cancelled;

    /** Creates a token that is not cancelled. */
    public MutableCancelToken() {
    }

    /** Requests cancellation. Idempotent. */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
