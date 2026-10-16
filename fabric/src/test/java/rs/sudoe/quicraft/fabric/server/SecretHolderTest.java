// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The shared secret's copies (docs/protocol.md 12.6): every array held is zeroed when it leaves. */
class SecretHolderTest {
    @Test
    void replacingTheSecretZeroesTheArrayItHeld() {
        SecretHolder holder = new SecretHolder();
        holder.set(new byte[] {1, 2, 3, 4});
        byte[] first = holder.peekForTest();

        holder.set(new byte[] {9, 9, 9, 9});

        assertArrayEquals(new byte[4], first, "the replaced copy was wiped");
        assertArrayEquals(new byte[] {9, 9, 9, 9}, holder.peekForTest());
    }

    @Test
    void wipeZeroesAndDropsTheHeldArray() {
        SecretHolder holder = new SecretHolder();
        byte[] original = {5, 6, 7, 8};
        holder.set(original);
        byte[] held = holder.peekForTest();
        assertNotSame(original, held, "the holder keeps its own copy");

        holder.wipe();

        assertArrayEquals(new byte[4], held);
        assertNull(holder.peekForTest());
        assertNull(holder.take());
    }

    @Test
    void takeHandsOverTheArrayAndForgetsIt() {
        SecretHolder holder = new SecretHolder();
        holder.set(new byte[] {1, 1});
        byte[] taken = holder.take();

        assertArrayEquals(new byte[] {1, 1}, taken);
        assertNull(holder.peekForTest());
        holder.wipe();
        assertArrayEquals(new byte[] {1, 1}, taken, "what was handed over is the caller's to zero");
    }
}
