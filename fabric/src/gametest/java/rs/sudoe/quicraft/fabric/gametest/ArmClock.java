// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.gametest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Arrival times on the client's Netty thread for one external arm join (ExternalArmGameTest),
 * at the same events BenchSession timestamps. Set by ConnectionTimingMixin.
 */
public final class ArmClock {
    static final AtomicLong helloAt = new AtomicLong();
    static final AtomicLong loginFinishedAt = new AtomicLong();
    static final AtomicLong configurationAt = new AtomicLong();
    static final AtomicLong playLoginAt = new AtomicLong();
    static final AtomicLong lastChunkAt = new AtomicLong();
    static final AtomicInteger chunks = new AtomicInteger();
    /** Round trips in nanoseconds, one per pong whose payload is a ping we sent. */
    static final ConcurrentLinkedQueue<Long> rtts = new ConcurrentLinkedQueue<>();

    private ArmClock() {}

    static void reset() {
        helloAt.set(0);
        loginFinishedAt.set(0);
        configurationAt.set(0);
        playLoginAt.set(0);
        lastChunkAt.set(0);
        chunks.set(0);
        rtts.clear();
    }

    public static void hello() {
        helloAt.compareAndSet(0, System.nanoTime());
    }

    public static void loginFinished() {
        loginFinishedAt.compareAndSet(0, System.nanoTime());
    }

    /** Any other packet after LoginFinished and before the play login is in CONFIGURATION. */
    public static void other() {
        if (loginFinishedAt.get() != 0 && playLoginAt.get() == 0) {
            configurationAt.compareAndSet(0, System.nanoTime());
        }
    }

    public static void playLogin() {
        playLoginAt.compareAndSet(0, System.nanoTime());
    }

    public static void chunk() {
        chunks.incrementAndGet();
        lastChunkAt.set(System.nanoTime());
    }

    public static void pong(long sentNanos) {
        long now = System.nanoTime();
        if (sentNanos > 0 && sentNanos <= now && ExternalArmGameTest.ownPing(sentNanos)) {
            rtts.add(now - sentNanos);
        }
    }
}
