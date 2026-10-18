// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.resource.PressureRanking;
import rs.sudoe.quicraft.core.time.StallClock;

class SessionResourcesTest {
    private static final long CAP = 1L << 20;

    /**
     * Deterministic cumulative source: one scripted reading per {@link #sample()} call. An unscripted call is
     * a test bug and fails loudly instead of inventing accounting a test wanted to rule out.
     */
    static final class Scripted implements StallClock {
        private final ArrayDeque<StallClock.Sample> readings = new ArrayDeque<StallClock.Sample>();
        private int taken;

        /** Appends the next reading; chainable. */
        Scripted reading(long nowNanos, long excludedNanos, boolean valid) {
            readings.addLast(new Reading(nowNanos, excludedNanos, valid));
            return this;
        }

        int taken() {
            return taken;
        }

        @Override
        public StallClock.Sample sample() {
            taken++;
            StallClock.Sample next = readings.pollFirst();
            if (next == null) {
                throw new AssertionError("unscripted sample #" + taken);
            }
            return next;
        }
    }

    private static final class Reading implements StallClock.Sample {
        private final long nowNanos;
        private final long excludedNanos;
        private final boolean valid;

        Reading(long nowNanos, long excludedNanos, boolean valid) {
            this.nowNanos = nowNanos;
            this.excludedNanos = excludedNanos;
            this.valid = valid;
        }

        @Override public long nowNanos() { return nowNanos; }
        @Override public long excludedNanos() { return excludedNanos; }
        @Override public boolean valid() { return valid; }
    }

    private static InetAddress ip(String host) throws Exception {
        return InetAddress.getByName(host);
    }

    private static NodePools node() {
        return new NodePools(16L << 20, 1L << 20, 16L << 20, 10, false, NodePools.MIN_EXCESS);
    }

    private static SessionResources resources(NodePools node, LongSupplier clock,
            SessionResources.Accounting accounting) {
        return new SessionResources(node, CAP, CAP, CAP, CAP, CAP, SessionResources.defaultProfile(), clock,
                accounting);
    }

    @Test
    void cumulativeModePinsOneWatchForBothAccessorsAndRejectsTheLegacyOnes() {
        final long[] now = {0};
        SessionResources resources = resources(node(), () -> now[0], SessionResources.Accounting.CUMULATIVE);
        assertSame(resources.progressWatch(), resources.stallClock(), "both accessors pin the one watch");
        assertThrows(IllegalStateException.class, resources::executorWatch);
        assertThrows(IllegalStateException.class, resources::stallPairing);
    }

    @Test
    void legacyModeKeepsThePerSideWatchAndPairingAndRejectsTheCumulativeAccessors() {
        SessionResources resources = resources(node(), () -> 0, SessionResources.Accounting.LEGACY);
        assertNotNull(resources.executorWatch());
        assertNotNull(resources.stallPairing());
        assertThrows(IllegalStateException.class, resources::progressWatch);
        assertThrows(IllegalStateException.class, resources::stallClock);
    }

    @Test
    void oldConstructorsStayLegacyIncludingAnExplicitWatch() {
        final long[] now = {0};
        SessionResources plain = new SessionResources(node(), CAP, CAP, CAP, CAP, CAP,
                SessionResources.defaultProfile(), () -> now[0]);
        assertNotNull(plain.executorWatch(), "the 8-argument constructor is legacy");
        assertThrows(IllegalStateException.class, plain::progressWatch);
        ExecutorStallWatch watch = new ExecutorStallWatch(() -> now[0]);
        SessionResources explicit = new SessionResources(node(), CAP, CAP, CAP, CAP, CAP,
                SessionResources.defaultProfile(), () -> now[0], watch);
        assertSame(watch, explicit.executorWatch(), "the explicit legacy watch is kept");
        assertThrows(IllegalStateException.class, explicit::progressWatch);
    }

    @Test
    void nullAccountingIsRejectedBeforeAnythingIsBuilt() {
        assertThrows(IllegalArgumentException.class, () -> resources(node(), () -> 0, null));
    }

    @Test
    void bothRankingsShareTheOneWatchAndCleanupSurvivesAnInvalidReading() throws Exception {
        final long[] now = {0};
        SessionResources resources = resources(node(), () -> now[0], SessionResources.Accounting.CUMULATIVE);
        InetAddress ip = ip("192.0.2.10");
        resources.outboundRanking().connect("o", ip, null);
        resources.inboundRanking().connect("i", ip, null);
        assertThrows(IllegalStateException.class, () -> resources.outboundRanking().view(() -> now[0]),
                "a cumulative ranking rejects a timestamped view");
        assertThrows(IllegalStateException.class, () -> resources.inboundRanking().progress("i", 1, 0),
                "a cumulative ranking rejects a timestamped call");
        assertEquals(0, resources.progressWatch().sample().excludedTotalNanos);

        resources.progressWatch().invalidate();
        assertThrows(StallClock.UnavailableException.class, () -> resources.outboundRanking().score("o"),
                "the outbound ranking samples the invalid shared watch");
        assertThrows(StallClock.UnavailableException.class, () -> resources.inboundRanking().score("i"),
                "the inbound ranking samples the invalid shared watch");
        // Releasing an already-mutated connection stays possible from the coherent invalid reading.
        resources.outboundRanking().disconnect("o");
        resources.inboundRanking().disconnect("i");
        assertEquals(0.0, resources.outboundRanking().score("o"), "the outbound owner left the ranking");
        assertEquals(0.0, resources.inboundRanking().score("i"), "the inbound owner left the ranking");
    }

    @Test
    void interleavedOperationsOnBothRankingsStayOneCoherentReadingSequence() throws Exception {
        final long[] now = {0};
        SessionResources resources = resources(node(), () -> now[0], SessionResources.Accounting.CUMULATIVE);
        InetAddress ip = ip("192.0.2.11");
        resources.outboundRanking().connect("o", ip, null);
        long token = resources.progressWatch().probeSubmitted();
        now[0] = 3_000_000_000L;
        resources.progressWatch().probeCompleted(token);
        resources.inboundRanking().connect("i", ip, null);
        resources.outboundRanking().setRetained("o", 1024);
        resources.inboundRanking().setRetained("i", 1024);
        resources.outboundRanking().progress("o", 256);
        resources.inboundRanking().progress("i", 256);
        now[0] = 4_000_000_000L;
        assertTrue(resources.outboundRanking().score("o") > 0);
        assertTrue(resources.inboundRanking().score("i") > 0);
        assertTrue(resources.progressWatch().sample().excludedTotalNanos >= 2_000_000_000L,
                "the one probe accrued a two-second stall for both rankings");
        resources.outboundRanking().disconnect("o");
        resources.inboundRanking().disconnect("i");
    }

    @Test
    void byteChangesDuringAnOpenStallAreAccountedOnTransportTime() throws Exception {
        final long[] now = {0};
        SessionResources resources = resources(node(), () -> now[0], SessionResources.Accounting.CUMULATIVE);
        PressureRanking ranking = resources.outboundRanking();
        InetAddress ip = ip("192.0.2.12");
        ranking.connect("o", ip, null);
        long token = resources.progressWatch().probeSubmitted();
        now[0] = 2_000_000_000L;
        ranking.setRetained("o", 4096);
        now[0] = 3_000_000_000L;
        resources.progressWatch().probeCompleted(token);
        now[0] = 4_000_000_000L;
        double small = ranking.score("o");
        assertTrue(small > 0, "bytes held through the closed stall count on transport time");
        ranking.setRetained("o", 8192);
        now[0] = 5_000_000_000L;
        double large = ranking.score("o");
        assertTrue(large > small, "the larger hold scores higher once time passes: " + small + " -> " + large);
        ranking.disconnect("o");
    }

    @Test
    void ownersShareOneRankingsIdentityState() throws Exception {
        final long[] now = {0};
        SessionResources resources = resources(node(), () -> now[0], SessionResources.Accounting.CUMULATIVE);
        PressureRanking ranking = resources.outboundRanking();
        InetAddress ip = ip("192.0.2.13");
        ranking.connect("slow", ip, null);
        ranking.connect("fast", ip, null);
        assertEquals(1, ranking.exactIpCount(), "both owners share one exact identity");
        now[0] = 1_000_000_000L;
        ranking.setRetained("slow", 4096);
        ranking.setRetained("fast", 1024);
        now[0] = 2_000_000_000L;
        assertTrue(ranking.score("slow") > 0);
        assertTrue(ranking.score("fast") > 0);
        ranking.disconnect("slow");
        assertTrue(ranking.score("fast") > 0, "the surviving owner keeps the shared accounting");
        ranking.disconnect("fast");
        assertEquals(0.0, ranking.score("fast"), "the second owner's departure empties it");
    }
}
