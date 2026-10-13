// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** Another process holds the state lock. The caller refuses; it never waits. */
public final class StoreBusyException extends UpdateRejectedException {

    public StoreBusyException() {
        super("another server uses this update directory");
    }
}
