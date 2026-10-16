// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import rs.sudoe.quicraft.core.Protocol;

/**
 * The life of one {@code /3} join: NEW, BINDING, ACTIVE, or FAILED. FAILED is terminal: nothing moves
 * a failed join anywhere, so a step that runs after a failure cannot resurrect it. ACTIVE means the
 * cutover is complete; until then a failure is a binding failure ({@code 0x10}, the client retries
 * over TCP, 12.14), and only afterwards an internal error.
 */
final class JoinState {
    enum Phase {
        NEW, BINDING, ACTIVE, FAILED
    }

    private Phase phase = Phase.NEW;

    synchronized Phase phase() {
        return phase;
    }

    /** NEW to BINDING; false when the join is not new (already started, or failed). */
    synchronized boolean startBinding() {
        if (phase != Phase.NEW) {
            return false;
        }
        phase = Phase.BINDING;
        return true;
    }

    /** BINDING to ACTIVE, once the cutover is complete; false from any other phase, FAILED included. */
    synchronized boolean activate() {
        if (phase != Phase.BINDING) {
            return false;
        }
        phase = Phase.ACTIVE;
        return true;
    }

    /**
     * Moves to FAILED. Returns the application close code to use, or -1 when the join had already
     * failed (the first failure alone closes).
     */
    synchronized int fail() {
        if (phase == Phase.FAILED) {
            return -1;
        }
        int code = phase == Phase.ACTIVE ? Protocol.CLOSE_INTERNAL_ERROR : Protocol.CLOSE_BINDING_FAILURE;
        phase = Phase.FAILED;
        return code;
    }

    synchronized boolean failed() {
        return phase == Phase.FAILED;
    }
}
