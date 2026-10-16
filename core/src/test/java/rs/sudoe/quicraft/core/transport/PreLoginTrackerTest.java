// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.resource.ResourcePools;

/** docs/protocol.md 12.13 and 13.17: staged /3 admission charges, clocks and promotion. */
class PreLoginTrackerTest {
    private static final long STAGED_N6 = 2L * 8192 + 6 + 4096 + 4096;

    @Test
    void theStagedShareIsTwoEightKibPlusNPlusTheReadBufferAndTheTlsEstimate() {
        assertEquals(STAGED_N6, PreLoginTracker.stagedShare(6));
        assertEquals(2L * 8192 + 1 + 4096 + 4096, PreLoginTracker.stagedShare(1));
        assertThrows(IllegalArgumentException.class, () -> PreLoginTracker.stagedShare(7));
    }

    @Test
    void theFirstAcceptedInitialChargesThePreLoginPoolAndNotTheNodeBudget() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        assertEquals(STAGED_N6, pools.held(ResourcePools.Pool.PRE_LOGIN));
        assertEquals(0, pools.held(ResourcePools.Pool.NODE));
        assertEquals(PreLoginTracker.State.STAGED, tracker.state());
    }

    @Test
    void aFullPreLoginPoolRefusesTheStagedCharge() {
        FakePools pools = new FakePools().capacity(ResourcePools.Pool.PRE_LOGIN, STAGED_N6 - 1);
        assertNull(PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock()));
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void earlyDataBeyondTheStagedShareIsChargedAndReleasedOnlyAsExtra() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        assertTrue(tracker.chargeEarlyData(1000));
        assertEquals(STAGED_N6 + 1000, pools.held(ResourcePools.Pool.PRE_LOGIN));
        tracker.releaseExtra(5000);
        assertEquals(STAGED_N6, pools.held(ResourcePools.Pool.PRE_LOGIN), "releases at most the extra");
    }

    @Test
    void aDeclaredFrameWithinTheStagedShareNeedsNoExtraCharge() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, tracker.chargeDeclaredFrame(1000, 5));
        assertEquals(STAGED_N6, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void aLargeLegalLoginAnswerIsChargedAtItsExactDeclaredLength() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        long declared = 900_000;
        assertEquals(PreLoginTracker.ChargeResult.CHARGED, tracker.chargeDeclaredFrame(declared, 3));
        assertEquals(STAGED_N6 + declared + 3, pools.held(ResourcePools.Pool.PRE_LOGIN));
        tracker.releaseExtra(declared + 3);
        assertEquals(STAGED_N6, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void aDeclaredFrameAboveOneMebibyteIsOversizedAndNeverCharged() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        assertEquals(PreLoginTracker.ChargeResult.OVERSIZED,
                tracker.chargeDeclaredFrame(PreLoginTracker.MAX_DECLARED_FRAME_BYTES + 1, 0));
        assertEquals(STAGED_N6, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void aFrameThatCannotBeChargedWaitsNonFatally() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        pools.capacity(ResourcePools.Pool.PRE_LOGIN, STAGED_N6 + 10);
        assertEquals(PreLoginTracker.ChargeResult.WAIT, tracker.chargeDeclaredFrame(900_000, 0));
        assertEquals(PreLoginTracker.State.STAGED, tracker.state());
    }

    @Test
    void theClientWaitDeadlineCountsOnlyUnpausedTime() {
        Clock clock = new Clock();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(new FakePools(), "c1", 1, true, clock);
        clock.advance(TimeUnit.SECONDS.toNanos(5));
        tracker.pauseClientWait();
        clock.advance(TimeUnit.SECONDS.toNanos(20)); // a slow session server: not the client's time
        assertFalse(tracker.expired());
        tracker.resumeClientWait();
        clock.advance(TimeUnit.SECONDS.toNanos(4));
        assertFalse(tracker.expired(), "only 9 s of client wait so far");
        clock.advance(TimeUnit.SECONDS.toNanos(1));
        assertTrue(tracker.expired(), "10 s of client wait");
    }

    @Test
    void nestedPausesResumeOnlyWhenTheLastOneEnds() {
        Clock clock = new Clock();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(new FakePools(), "c1", 1, true, clock);
        tracker.pauseClientWait();
        tracker.pauseClientWait();
        clock.advance(TimeUnit.SECONDS.toNanos(9));
        tracker.resumeClientWait();
        clock.advance(TimeUnit.SECONDS.toNanos(9));
        assertFalse(tracker.expired(), "still paused by the outer wait");
        tracker.resumeClientWait();
        clock.advance(TimeUnit.SECONDS.toNanos(1));
        assertFalse(tracker.expired(), "paused time is excluded from the client wait");
    }

    @Test
    void theThirtySecondHardCapHoldsEvenWhilePaused() {
        Clock clock = new Clock();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(new FakePools(), "c1", 1, true, clock);
        tracker.pauseClientWait();
        clock.advance(TimeUnit.SECONDS.toNanos(29));
        assertFalse(tracker.expired());
        clock.advance(TimeUnit.SECONDS.toNanos(1));
        assertTrue(tracker.expired(), "30 s from the first accepted packet");
    }

    @Test
    void onlineLoginSuccessPromotesTheFullExposureFromTheNodeBudget() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        tracker.onLoginSuccess();
        assertEquals(PreLoginTracker.State.PROMOTED, tracker.state());
        assertEquals(TransportConfig.V3_INITIAL_EXPOSURE_BYTES, pools.held(ResourcePools.Pool.NODE));
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));
        assertFalse(tracker.expired(), "a promoted connection has no pre-login deadline");
    }

    @Test
    void offlineLoginSuccessStaysStagedUntilActivation() {
        FakePools pools = new FakePools();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, false, new Clock());
        tracker.onLoginSuccess();
        assertEquals(PreLoginTracker.State.STAGED, tracker.state());
        tracker.onActivation();
        assertEquals(PreLoginTracker.State.PROMOTED, tracker.state());
        assertEquals(TransportConfig.V3_INITIAL_EXPOSURE_BYTES, pools.held(ResourcePools.Pool.NODE));
    }

    @Test
    void promotionReservesTheNodeShareBeforeItReleasesThePreLoginCharge() {
        // Both pools exactly fit the promotion only if the node reservation is made first while the
        // staged charge is still held: 24 KiB in NODE plus the staged charge in PRE_LOGIN at once.
        FakePools pools = new FakePools()
                .capacity(ResourcePools.Pool.NODE, TransportConfig.V3_INITIAL_EXPOSURE_BYTES)
                .capacity(ResourcePools.Pool.PRE_LOGIN, STAGED_N6);
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        pools.log.clear();
        assertTrue(tracker.promote());
        assertEquals(PreLoginTracker.State.PROMOTED, tracker.state());
        assertEquals(java.util.Arrays.asList("reserve NODE", "release PRE_LOGIN"), pools.log,
                "the node share is reserved before the staged charge is released");
    }

    @Test
    void anExpiredStagedConnectionNeverPromotesEvenWhenTheNodeBudgetHasRoom() {
        FakePools pools = new FakePools().capacity(ResourcePools.Pool.NODE, 1000);
        Clock clock = new Clock();
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, clock);
        tracker.onLoginSuccess();
        assertEquals(PreLoginTracker.State.STAGED, tracker.state());
        clock.advance(TimeUnit.SECONDS.toNanos(31));
        assertTrue(tracker.expired());
        pools.capacity(ResourcePools.Pool.NODE, TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
        assertFalse(tracker.retryPromotion(), "the hard cap wins over a freed node budget");
        assertFalse(tracker.promote());
        tracker.onActivation();
        assertEquals(PreLoginTracker.State.STAGED, tracker.state());
        assertEquals(0, pools.held(ResourcePools.Pool.NODE));
    }

    @Test
    void aFullNodeBudgetLeavesTheConnectionStagedAndRetryable() {
        FakePools pools = new FakePools().capacity(ResourcePools.Pool.NODE, 1000);
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        tracker.onLoginSuccess();
        assertEquals(PreLoginTracker.State.STAGED, tracker.state(), "the caller waits non-fatally");
        assertEquals(STAGED_N6, pools.held(ResourcePools.Pool.PRE_LOGIN));
        pools.capacity(ResourcePools.Pool.NODE, TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
        assertTrue(tracker.promote());
    }

    @Test
    void shedOnlyReleasesAStagedConnectionAndNeverAPromotedOne() {
        FakePools pools = new FakePools();
        PreLoginTracker staged = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        staged.onShed();
        assertEquals(PreLoginTracker.State.CLOSED, staged.state());
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));

        PreLoginTracker promoted = PreLoginTracker.tryOpen(pools, "c2", 6, true, new Clock());
        promoted.onLoginSuccess();
        promoted.onShed();
        assertEquals(PreLoginTracker.State.PROMOTED, promoted.state());
        assertEquals(TransportConfig.V3_INITIAL_EXPOSURE_BYTES, pools.held(ResourcePools.Pool.NODE));
    }

    @Test
    void closeReleasesTheHeldPoolExactlyOnce() {
        FakePools pools = new FakePools();
        PreLoginTracker staged = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        staged.close();
        staged.close();
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));

        PreLoginTracker promoted = PreLoginTracker.tryOpen(pools, "c2", 6, true, new Clock());
        promoted.onLoginSuccess();
        promoted.close();
        promoted.close();
        assertEquals(0, pools.held(ResourcePools.Pool.NODE));
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    @Test
    void aPromotionTheNodeBudgetRefusedIsRetriedUntilItSucceeds() {
        FakePools pools = new FakePools().capacity(ResourcePools.Pool.NODE, 1000);
        PreLoginTracker tracker = PreLoginTracker.tryOpen(pools, "c1", 6, true, new Clock());
        assertFalse(tracker.retryPromotion(), "nothing requested yet");
        tracker.onLoginSuccess();
        assertFalse(tracker.retryPromotion(), "the node budget is still full");
        assertEquals(PreLoginTracker.State.STAGED, tracker.state());
        pools.capacity(ResourcePools.Pool.NODE, TransportConfig.V3_INITIAL_EXPOSURE_BYTES);
        assertTrue(tracker.retryPromotion(), "a retry succeeds once the node budget frees space");
        assertEquals(PreLoginTracker.State.PROMOTED, tracker.state());
        assertEquals(0, pools.held(ResourcePools.Pool.PRE_LOGIN));
    }

    /** A monotonic clock the test moves by hand. */
    private static final class Clock implements LongSupplier {
        private long nanos = 1_000;

        @Override
        public long getAsLong() {
            return nanos;
        }

        void advance(long by) {
            nanos += by;
        }
    }

    /** Pools with optional capacities; a reserve that would exceed a capacity fails. */
    static final class FakePools implements ResourcePools {
        private final Map<Pool, Long> held = new EnumMap<>(Pool.class);
        private final Map<Pool, Long> caps = new EnumMap<>(Pool.class);
        final java.util.List<String> log = new java.util.ArrayList<>();

        FakePools capacity(Pool pool, long bytes) {
            caps.put(pool, bytes);
            return this;
        }

        long held(Pool pool) {
            return held.getOrDefault(pool, 0L);
        }

        @Override
        public boolean reserve(Pool pool, Object owner, long bytes) {
            long next = held(pool) + bytes;
            if (caps.containsKey(pool) && next > caps.get(pool)) {
                return false;
            }
            held.put(pool, next);
            log.add("reserve " + pool);
            return true;
        }

        @Override
        public void release(Pool pool, Object owner, long bytes) {
            held.put(pool, held(pool) - bytes);
            log.add("release " + pool);
        }

        @Override
        public boolean promote(Pool from, Pool to, Object owner, long bytes) {
            if (!reserve(to, owner, bytes)) {
                return false;
            }
            release(from, owner, bytes);
            return true;
        }
    }
}
