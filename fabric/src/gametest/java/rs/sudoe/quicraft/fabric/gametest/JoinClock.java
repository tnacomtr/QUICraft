// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When the client's Netty thread received the play login and each chunk, so join timings aren't
 * quantized to game ticks (JoinTimingGameTest). Set by ConnectionTimingMixin.
 */
public final class JoinClock {
    static final AtomicLong activeAt = new AtomicLong();
    static final AtomicLong loginAt = new AtomicLong();
    static final AtomicLong lastChunkAt = new AtomicLong();
    static final AtomicInteger chunks = new AtomicInteger();

    private JoinClock() {}

    static void reset() {
        activeAt.set(0);
        loginAt.set(0);
        lastChunkAt.set(0);
        chunks.set(0);
    }

    public static void active() {
        activeAt.compareAndSet(0, System.nanoTime());
    }

    public static void login() {
        loginAt.compareAndSet(0, System.nanoTime());
    }

    public static void chunk() {
        chunks.incrementAndGet();
        lastChunkAt.set(System.nanoTime());
    }
}
