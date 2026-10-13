// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

/**
 * Both copies (current and {@code .prev}) of one state file are invalid. Callers decide between
 * disabling staging ({@code revokedValid == true}, state.json unavailable) and disabling the updater
 * ({@code revokedValid == false}). Neither file is ever reset silently.
 */
public final class StateUnavailableException extends UpdateRejectedException {

    private final boolean revokedValid;

    public StateUnavailableException(boolean revokedValid, String message) {
        super(message);
        this.revokedValid = revokedValid;
    }

    /** True when revoked.json is readable, so only state.json needs a reset. */
    public boolean revokedValid() {
        return revokedValid;
    }
}
