// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import java.util.Arrays;

/**
 * The one copy of Minecraft's shared secret {@code S} a {@code /3} join keeps between login and the
 * start of binding (docs/protocol.md 12.6): every array it ever held is zeroed when it is replaced,
 * taken, or the join ends. The caller's own array is the caller's to zero.
 *
 * <p>Threading: guarded by this object; login and the event loop may both touch it.</p>
 */
final class SecretHolder {
    private byte[] held;

    /** Keeps a private copy of {@code encoded}, zeroing whatever it held before. */
    synchronized void set(byte[] encoded) {
        wipe();
        held = encoded == null ? null : encoded.clone();
    }

    /** Hands the held array to the caller, who must zero it; the holder keeps nothing. */
    synchronized byte[] take() {
        byte[] out = held;
        held = null;
        return out;
    }

    /** Zeroes and drops the held array. */
    synchronized void wipe() {
        if (held != null) {
            Arrays.fill(held, (byte) 0);
            held = null;
        }
    }

    /** Tests: the array currently held, not a copy. */
    synchronized byte[] peekForTest() {
        return held;
    }
}
