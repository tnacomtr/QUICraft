// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/** A fail-closed rejection of untrusted updater metadata. */
public final class UpdateRejectedException extends Exception {
    public UpdateRejectedException(String message) {
        super(message);
    }

    public UpdateRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
