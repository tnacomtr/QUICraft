// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Terminal receive cleanup must finish even when one charge owner fails. */
final class ReceiveCleanup {
    private ReceiveCleanup() { }

    static void run(Runnable... steps) {
        Throwable failure = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (Throwable t) {
                if (failure == null) failure = t;
                else if (failure != t) failure.addSuppressed(t);
            }
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure != null) throw new IllegalStateException("receive cleanup failed", failure);
    }
}
