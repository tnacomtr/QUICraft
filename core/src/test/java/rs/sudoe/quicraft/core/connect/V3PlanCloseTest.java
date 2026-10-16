// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.CloseReason;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.connect.TransportSelection.PinState;
import rs.sudoe.quicraft.core.connect.V3Plan.CloseClass;
import rs.sudoe.quicraft.core.connect.V3Plan.CloseEvent;
import rs.sudoe.quicraft.core.connect.V3Plan.Phase;
import rs.sudoe.quicraft.core.pin.PinStore;
import rs.sudoe.quicraft.core.pin.RebindState;
import rs.sudoe.quicraft.core.tls.Fingerprint;

/**
 * The client's {@code /3} close classification and its failure-cache effects (docs/protocol.md
 * 12.13 pre-login shed, 12.14 fixed resource window and pre-verification allowance, 13.12 dead path
 * and repeated unseen-prerequisite closes, 13.20's cache tests, Answered question 5).
 */
class V3PlanCloseTest {
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final long SECOND = TimeUnit.SECONDS.toMillis(1);
    private static final int QUIC_PORT = 25570;
    private static final String HOST = "mc.example.com";
    private static final int LOGICAL_PORT = 25565;
    private static final byte[] UNSEEN = "unseen-prerequisite".getBytes(StandardCharsets.US_ASCII);
    private static final Executor INLINE = Runnable::run;

    /** The client state one join shares with the next: one clock, one cache, one limiter state. */
    private static final class Client {
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        final FailureCache failures = FailureCache.load(null, clock::get);
        final RebindState rebinds = rebinds(clock);
        final InetAddress address = address();
        final Fingerprint fingerprint = fingerprint('c');

        V3Plan join() {
            return join(ConnectDecision.Mode.AUTO, null);
        }

        V3Plan join(ConnectDecision.Mode mode, RebindState.Attempt attempt) {
            return new V3Plan(mode, attempt == null ? PinState.NONE : PinState.CHANGED, attempt,
                    fingerprint, new byte[32], PinStore.load(null), failures, rebinds, INLINE, address,
                    QUIC_PORT, HOST, LOGICAL_PORT);
        }

        /** A join that reached play: SERVER_SWITCH verified, /3 active. */
        V3Plan active() {
            V3Plan plan = join();
            plan.serverSwitchVerified();
            return plan;
        }

        /** What the race records when the QUIC handshake wins the next join. */
        void handshakeWon() {
            failures.recordSuccess(address, QUIC_PORT, fingerprint);
        }

        boolean cacheAllowsQuic() {
            return failures.allows(address, QUIC_PORT, fingerprint)
                    && !failures.isActivationBlocked(address, QUIC_PORT, fingerprint, clock.get());
        }

        boolean resourceWindow() {
            return rebinds.resourceWindowActive(HOST, LOGICAL_PORT, clock.get());
        }

        void advance(long millis) {
            clock.addAndGet(millis);
        }
    }

    /** RebindState's clock-taking loader is package-private in the pin package; reach it reflectively. */
    private static RebindState rebinds(AtomicLong clock) {
        try {
            java.lang.reflect.Method load = RebindState.class.getDeclaredMethod("load", java.nio.file.Path.class,
                    java.util.function.LongSupplier.class);
            load.setAccessible(true);
            java.util.function.LongSupplier supplier = clock::get;
            return (RebindState) load.invoke(null, null, supplier);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InetAddress address() {
        try {
            return InetAddress.getByName("192.0.2.10");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Fingerprint fingerprint(char digit) {
        StringBuilder text = new StringBuilder("sha256:");
        for (int i = 0; i < 64; i++) {
            text.append(digit);
        }
        return Fingerprint.parse(text.toString());
    }

    private static CloseEvent peer(boolean application, long code, byte[] phrase) {
        return CloseEvent.peerClose(new CloseReason(application, code, phrase));
    }

    private static CloseEvent unseenClose() {
        return peer(true, Protocol.CLOSE_RESOURCE, UNSEEN);
    }

    private static CloseEvent genericResource() {
        return peer(true, Protocol.CLOSE_RESOURCE, new byte[0]);
    }

    // ---- classification (pure) ----

    @Test
    void onlyAnApplication0x12WithTheExactPhraseWhileActiveCounts() {
        assertEquals(CloseClass.UNSEEN_PREREQUISITE, V3Plan.classify(unseenClose(), true, true, true));
        assertEquals(CloseClass.NON_RESOURCE,
                V3Plan.classify(peer(false, Protocol.CLOSE_RESOURCE, UNSEEN), true, true, true),
                "a transport CONNECTION_CLOSE (0x1c) never counts, whatever its code and phrase");
        assertEquals(CloseClass.NON_RESOURCE,
                V3Plan.classify(peer(true, Protocol.CLOSE_BINDING_FAILURE, UNSEEN), true, true, true),
                "a wrong code keeps its normal (non-resource) classification");
        assertEquals(CloseClass.PEER_RESOURCE_VERIFIED,
                V3Plan.classify(peer(true, Protocol.CLOSE_RESOURCE,
                        "unseen-prerequisite ".getBytes(StandardCharsets.US_ASCII)), true, true, true),
                "a phrase that is not exactly unseen-prerequisite is a generic resource close");
        assertEquals(CloseClass.PEER_RESOURCE_VERIFIED,
                V3Plan.classify(peer(true, Protocol.CLOSE_RESOURCE,
                        "UNSEEN-PREREQUISITE".getBytes(StandardCharsets.US_ASCII)), true, true, true));
        assertEquals(CloseClass.PEER_RESOURCE_VERIFIED, V3Plan.classify(genericResource(), true, true, true));
        assertEquals(CloseClass.PEER_RESOURCE_UNVERIFIED, V3Plan.classify(unseenClose(), true, false, false),
                "before SERVER_SWITCH verifies only 12.14's bounded allowance applies");
        assertEquals(CloseClass.PEER_RESOURCE_VERIFIED, V3Plan.classify(unseenClose(), true, true, false),
                "after a failed activation /3 is not active, so it does not count");
    }

    @Test
    void aPeer0x12BeforeLoginSuccessIsAShedUnlessThePathIsDead() {
        assertEquals(CloseClass.PRE_LOGIN_SHED, V3Plan.classify(genericResource(), false, false, false));
        assertEquals(CloseClass.PRE_LOGIN_SHED, V3Plan.classify(unseenClose(), false, false, false));
        assertEquals(CloseClass.PRE_LOGIN_SHED,
                V3Plan.classify(genericResource().path(9_999, 9_999), false, false, false));
        assertEquals(CloseClass.DEAD_PATH_SHORTEST,
                V3Plan.classify(genericResource().path(10_000, 0), false, false, false),
                "13.12's zero-progress rule takes precedence over the pre-login shed");
        assertEquals(CloseClass.DEAD_PATH_SHORTEST,
                V3Plan.classify(unseenClose().path(10_000, 0), true, true, true));
        assertEquals(CloseClass.NON_RESOURCE,
                V3Plan.classify(peer(true, Protocol.CLOSE_BINDING_FAILURE, new byte[0]), false, false, false),
                "any other code before Login Success keeps its normal classification");
    }

    @Test
    void localClosesAreClassifiedByTheirPathAndTheirCause() {
        assertEquals(CloseClass.DEAD_PATH, V3Plan.classify(CloseEvent.idleTimeout(), true, true, true));
        assertEquals(CloseClass.LOCAL_RESOURCE,
                V3Plan.classify(CloseEvent.localResourceAbort(10_000), true, true, true));
        assertEquals(CloseClass.DEAD_PATH, V3Plan.classify(
                CloseEvent.localResourceAbort(10_000).path(10_000, 10_000), false, false, false),
                "data outstanding and no authenticated packet for the whole deadline is a dead path");
        assertEquals(CloseClass.LOCAL_RESOURCE, V3Plan.classify(
                CloseEvent.localResourceAbort(10_000).path(10_000, 2_000), true, true, true),
                "a peer that still sends authenticated packets is alive");
        assertEquals(CloseClass.LOCAL_RESOURCE, V3Plan.classify(
                CloseEvent.localResourceAbort(10_000).path(0, 10_000), true, true, true),
                "an idle connection whose probes are ACKed is not zero progress");
        assertEquals(CloseClass.UNSEEN_PREREQUISITE,
                V3Plan.classify(CloseEvent.blockedHeadExpiry(false), true, true, true));
        assertEquals(CloseClass.LOCAL_RESOURCE,
                V3Plan.classify(CloseEvent.blockedHeadExpiry(true), true, true, true),
                "a stall-affected or locally withheld expiry is generic");
        assertEquals(CloseClass.LOCAL_RESOURCE,
                V3Plan.classify(CloseEvent.blockedHeadExpiry(false), true, false, false),
                "before SERVER_SWITCH the client's own timer does not count");
        assertEquals(CloseClass.NON_RESOURCE, V3Plan.classify(CloseEvent.localFailure(), true, true, true));
    }

    // ---- 13.12: repeated unseen-prerequisite closes ----

    @Test
    void threeSuccessiveJoinsWithThePhraseWriteTheShortestStep() {
        Client c = new Client();
        V3Plan first = c.active();
        assertFalse(first.closed(Phase.PLAY, unseenClose(), "unseen"), "a play close never retries");
        assertEquals(CloseClass.UNSEEN_PREREQUISITE, first.closeClass());
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint), "one counted close writes no entry");

        // The fixed resource window passes, the next join wins its handshake and is closed again.
        c.advance(MINUTE + SECOND);
        assertFalse(c.resourceWindow());
        c.handshakeWon();
        c.active().closed(Phase.PLAY, unseenClose(), "unseen");
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "two counted events within ten minutes: the next join uses TCP");
        c.advance(MINUTE - SECOND);
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
        c.advance(SECOND);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "the entry is the SHORTEST step: one minute");

        c.handshakeWon();
        c.active().closed(Phase.PLAY, unseenClose(), "unseen");
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "the third join pairs with the second");
        c.advance(MINUTE);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint), "still the shortest step");
        // The exponent never advanced: the next ordinary failure starts at one minute.
        c.failures.recordFailure(c.address, QUIC_PORT, c.fingerprint);
        c.advance(MINUTE);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
    }

    @Test
    void countedEventsMoreThanTenMinutesApartWriteNothing() {
        Client c = new Client();
        c.active().closed(Phase.PLAY, unseenClose(), null);
        c.advance(10 * MINUTE);
        c.active().closed(Phase.PLAY, unseenClose(), null);
        assertTrue(c.cacheAllowsQuic());
    }

    @Test
    void theClientsOwnBlockedHeadTimerCountsToo() {
        Client c = new Client();
        c.active().closed(Phase.PLAY, CloseEvent.blockedHeadExpiry(false), null);
        c.advance(2 * MINUTE);
        c.active().closed(Phase.PLAY, unseenClose(), null);
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
    }

    @Test
    void theShortestStepNeverShortensALongerWindowNorRaisesItsExponent() {
        Client c = new Client();
        for (int i = 0; i < 3; i++) {
            c.failures.recordFailure(c.address, QUIC_PORT, c.fingerprint); // 1, 2, then a 4-minute window
        }
        c.active().closed(Phase.PLAY, unseenClose(), null);
        c.active().closed(Phase.PLAY, unseenClose(), null);
        c.advance(4 * MINUTE - SECOND);
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint), "the 4-minute window is kept");
        c.advance(SECOND);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
        c.failures.recordFailure(c.address, QUIC_PORT, c.fingerprint);
        c.advance(8 * MINUTE - SECOND);
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint), "fourth failure: 8 minutes, not 16");
        c.advance(SECOND);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
    }

    /** The closes that must never count as unseen-prerequisite events, each with its own reason. */
    private static CloseEvent[] negatives() {
        return new CloseEvent[] {
            peer(false, Protocol.CLOSE_RESOURCE, UNSEEN), // transport close 0x1c
            peer(true, 0x13, UNSEEN), // wrong code
            peer(true, Protocol.CLOSE_RESOURCE, "unseen".getBytes(StandardCharsets.US_ASCII)), // wrong phrase
            peer(true, Protocol.CLOSE_RESOURCE, new byte[0]), // no phrase
            CloseEvent.blockedHeadExpiry(true), // stall-affected or withheld
        };
    }

    @Test
    void everyNonMatchingCloseKeepsItsOwnClassificationAndWritesNoUnseenEntry() {
        for (CloseEvent negative : negatives()) {
            Client c = new Client();
            for (int i = 0; i < 3; i++) {
                c.active().closed(Phase.PLAY, negative, null);
                // Checked straight after the close: a wrongly written shortest-step entry would
                // still be live here, and would be long expired after any advance of the clock.
                assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                        "no shortest-step entry right after " + negative.kind() + " close " + (i + 1));
                assertFalse(V3Plan.classify(negative, true, true, true) == CloseClass.UNSEEN_PREREQUISITE,
                        "and it is not classified as an unseen-prerequisite event: " + negative.kind());
                c.advance(MINUTE + SECOND);
            }
        }
    }

    @Test
    void aNonMatchingCloseNeverPairsWithALaterValidOne() {
        for (CloseEvent negative : negatives()) {
            Client c = new Client();
            c.active().closed(Phase.PLAY, negative, null);
            assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
            c.advance(MINUTE);
            // The first valid event: a counted event, but the negative must not have been one.
            c.active().closed(Phase.PLAY, unseenClose(), null);
            assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                    "one valid event after " + negative.kind() + " is still only one counted event");
            c.advance(MINUTE);
            // The second valid event pairs with the first, not with the negative.
            c.active().closed(Phase.PLAY, unseenClose(), null);
            assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                    "two valid events pair after " + negative.kind());
        }
    }

    @Test
    void anUnseenCloseBeforeServerSwitchNeverPairsWithALaterValidOne() {
        Client c = new Client();
        V3Plan early = c.join();
        early.loginSucceeded();
        early.closed(Phase.JOINING, unseenClose(), null);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "a pre-verification unseen-prerequisite close is not counted");
        c.advance(MINUTE);
        c.active().closed(Phase.PLAY, unseenClose(), null);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "so the first counted event is this one, and it writes nothing");
    }

    @Test
    void thePhraseBeforeServerSwitchUsesOnlyTheAllowance() {
        Client c = new Client();
        for (int i = 0; i < 3; i++) {
            V3Plan plan = c.join();
            plan.loginSucceeded();
            plan.closed(Phase.JOINING, unseenClose(), null);
            assertEquals(CloseClass.PEER_RESOURCE_UNVERIFIED, plan.closeClass());
            c.advance(MINUTE + SECOND);
        }
        assertTrue(c.cacheAllowsQuic(), "three pre-verification reports are inside the allowance");
    }

    @Test
    void aTransportCloseWithTheCodeKeepsNormalBackoff() {
        Client c = new Client();
        V3Plan plan = c.active();
        plan.closed(Phase.PLAY, peer(false, Protocol.CLOSE_RESOURCE, UNSEEN), null);
        assertEquals(CloseClass.NON_RESOURCE, plan.closeClass());
        assertFalse(c.cacheAllowsQuic(), "a non-resource close records the activation failure as before");
    }

    // ---- 12.14: local resource aborts and the fixed window ----

    @Test
    void aGenericLocalResourceAbortWritesNoEntryAndStartsTheFixedWindow() {
        Client c = new Client();
        for (int i = 0; i < 5; i++) {
            V3Plan plan = c.active();
            plan.closed(Phase.PLAY, CloseEvent.localResourceAbort(10_000), "resource");
            assertTrue(c.cacheAllowsQuic(), "a generic local resource abort never writes the cache");
            assertTrue(c.resourceWindow());
            c.advance(MINUTE - 1);
            assertTrue(c.resourceWindow());
            c.advance(1);
            assertFalse(c.resourceWindow(), "fixed 60 seconds, never doubled");
        }
    }

    @Test
    void aLocalAbortOnADeadPathCountsLikeAnIdleTimeout() {
        Client c = new Client();
        V3Plan plan = c.active();
        plan.closed(Phase.PLAY, CloseEvent.localResourceAbort(10_000).path(12_000, 11_000), null);
        assertEquals(CloseClass.DEAD_PATH, plan.closeClass());
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
        assertFalse(c.resourceWindow());
        c.advance(MINUTE);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
        c.active().closed(Phase.PLAY, CloseEvent.idleTimeout(), null);
        c.advance(MINUTE);
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "a second transport failure doubles, as §6 does for an idle timeout");
    }

    @Test
    void aPeer0x12AfterTenUnackedSecondsIsATransportFailureAtTheShortestStep() {
        Client c = new Client();
        V3Plan plan = c.active();
        plan.closed(Phase.PLAY, genericResource().path(10_000, 10_000), null);
        assertEquals(CloseClass.DEAD_PATH_SHORTEST, plan.closeClass());
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
        c.advance(MINUTE);
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "when the loss stops, the next join after the window uses QUIC");

        V3Plan live = c.active();
        live.closed(Phase.PLAY, genericResource().path(9_000, 9_000), null);
        assertEquals(CloseClass.PEER_RESOURCE_VERIFIED, live.closeClass());
        assertTrue(c.failures.allows(c.address, QUIC_PORT, c.fingerprint),
                "a shared-pool 0x12 on a live path does not touch the cache");
    }

    // ---- 12.14: pre-verification allowance ----

    @Test
    void theFourthPreVerificationReportInTenMinutesIsTreatedLike0x10() {
        Client c = new Client();
        for (int i = 0; i < 3; i++) {
            V3Plan plan = c.join();
            plan.loginSucceeded();
            assertTrue(plan.closed(Phase.JOINING, genericResource(), "server busy"), "join-time TCP retry");
            assertTrue(c.cacheAllowsQuic(), "report " + (i + 1) + " is inside the allowance");
            assertTrue(c.resourceWindow());
            c.advance(MINUTE + SECOND);
        }
        V3Plan fourth = c.join();
        fourth.loginSucceeded();
        assertTrue(fourth.closed(Phase.JOINING, genericResource(), "server busy"));
        assertFalse(c.cacheAllowsQuic(), "the fourth report follows normal backoff");
        assertFalse(c.resourceWindow(), "and gets no fixed-window exemption");
    }

    @Test
    void theAllowanceRollsAndCountsReportsTreatedLike0x10() {
        Client c = new Client();
        for (int i = 0; i < 4; i++) {
            V3Plan plan = c.join();
            plan.loginSucceeded();
            plan.closed(Phase.JOINING, genericResource(), null);
            c.advance(2 * MINUTE);
        }
        // Reports at 0, 2, 4, 6 minutes; now 8. Three retained (2, 4, 6) are all inside ten minutes.
        c.failures.clearActivationFailure(c.address, QUIC_PORT, c.fingerprint);
        V3Plan fifth = c.join();
        fifth.loginSucceeded();
        fifth.closed(Phase.JOINING, genericResource(), null);
        assertFalse(c.cacheAllowsQuic(), "the fourth report, though it was over the allowance, still counts");

        // At 12:00:001 the report at 2 minutes has rolled out; retained: 4, 6, 8, so still three.
        c.failures.clearActivationFailure(c.address, QUIC_PORT, c.fingerprint);
        c.advance(4 * MINUTE + 1);
        V3Plan sixth = c.join();
        sixth.loginSucceeded();
        sixth.closed(Phase.JOINING, genericResource(), null);
        assertFalse(c.cacheAllowsQuic());

        // Retained now 6, 8, 12; at 16:00:002, 6 has rolled out, so two prior remain: allowed.
        c.failures.clearActivationFailure(c.address, QUIC_PORT, c.fingerprint);
        c.advance(4 * MINUTE + 1);
        V3Plan seventh = c.join();
        seventh.loginSucceeded();
        seventh.closed(Phase.JOINING, genericResource(), null);
        assertTrue(c.cacheAllowsQuic());
        assertTrue(c.resourceWindow());
    }

    @Test
    void aSuccessfulActivationDoesNotResetTheAllowance() throws Exception {
        Client c = new Client();
        for (int i = 0; i < 3; i++) {
            V3Plan plan = c.join();
            plan.loginSucceeded();
            plan.closed(Phase.JOINING, genericResource(), null);
            c.advance(MINUTE + SECOND);
        }
        c.rebinds.recordSuccess(HOST, LOGICAL_PORT);
        V3Plan fourth = c.join();
        fourth.loginSucceeded();
        fourth.closed(Phase.JOINING, genericResource(), null);
        assertFalse(c.cacheAllowsQuic());
    }

    // ---- 12.13 / 12.14: pre-login sheds ----

    @Test
    void aPreLoginShedRetriesTcpOnceAndWritesNothing() {
        Client c = new Client();
        V3Plan plan = c.join();
        assertTrue(plan.closed(Phase.JOINING, genericResource(), "server busy"), "TCP for that join");
        assertEquals(CloseClass.PRE_LOGIN_SHED, plan.closeClass());
        assertFalse(plan.udpHintAllowed(), "a shed is kept out of the UDP-unreachable hint");
        assertTrue(c.cacheAllowsQuic());
        assertFalse(c.resourceWindow(), "the next join tries QUIC as usual");
        assertFalse(plan.closed(Phase.JOINING, genericResource(), "server busy"), "one TCP retry per connection");

        V3Plan quicOnly = c.join(ConnectDecision.Mode.QUIC_ONLY, null);
        assertFalse(quicOnly.closed(Phase.JOINING, genericResource(), null), "quic-only fails instead");
    }

    @Test
    void threeConsecutiveShedsWithinTenMinutesStartTheFixedWindowWithoutACacheEntry() {
        Client c = new Client();
        c.join().closed(Phase.JOINING, genericResource(), null);
        c.advance(MINUTE);
        c.join().closed(Phase.JOINING, genericResource(), null);
        assertFalse(c.resourceWindow());
        c.advance(MINUTE);
        c.join().closed(Phase.JOINING, genericResource(), null);
        assertTrue(c.resourceWindow(), "the third consecutive shed starts the fixed window");
        assertTrue(c.cacheAllowsQuic(), "still no failure-cache entry");
        c.advance(MINUTE);
        assertFalse(c.resourceWindow());
    }

    @Test
    void shedsSpreadOverMoreThanTenMinutesOrBrokenByASuccessDoNotStartTheWindow() throws Exception {
        Client c = new Client();
        c.join().closed(Phase.JOINING, genericResource(), null);
        c.advance(6 * MINUTE);
        c.join().closed(Phase.JOINING, genericResource(), null);
        c.advance(5 * MINUTE);
        c.join().closed(Phase.JOINING, genericResource(), null);
        assertFalse(c.resourceWindow(), "the first shed is more than ten minutes old");

        Client d = new Client();
        d.join().closed(Phase.JOINING, genericResource(), null);
        d.join().closed(Phase.JOINING, genericResource(), null);
        V3Plan success = d.join();
        assertTrue(success.activated(rs.sudoe.quicraft.core.binding.BindingTestSessions.activatedClient(rs.sudoe.quicraft.core.binding.BindingStateMachine.MODE_OFFLINE))
                .get(5, TimeUnit.SECONDS));
        d.join().closed(Phase.JOINING, genericResource(), null);
        assertFalse(d.resourceWindow(), "a successful activation ends the run of consecutive sheds");
    }

    @Test
    void shedsDoNotCountTowardThePreVerificationAllowance() {
        Client c = new Client();
        for (int i = 0; i < 5; i++) {
            c.join().closed(Phase.JOINING, genericResource(), null);
        }
        c.advance(MINUTE + SECOND);
        for (int i = 0; i < 3; i++) {
            V3Plan plan = c.join();
            plan.loginSucceeded();
            plan.closed(Phase.JOINING, genericResource(), null);
            assertTrue(c.cacheAllowsQuic(), "report " + (i + 1) + " is still inside the allowance");
            c.advance(MINUTE + SECOND);
        }
    }

    @Test
    void aShedOnADeadPathIsATransportFailure() {
        Client c = new Client();
        V3Plan plan = c.join();
        assertTrue(plan.closed(Phase.JOINING, genericResource().path(10_000, 0), null));
        assertEquals(CloseClass.DEAD_PATH_SHORTEST, plan.closeClass());
        assertTrue(plan.udpHintAllowed());
        assertFalse(c.failures.allows(c.address, QUIC_PORT, c.fingerprint));
    }

    // ---- re-bind limiter interaction ----

    @Test
    void aResourceCloseReleasesTheRebindAttemptWithoutAdvancingItsWindow() {
        Client c = new Client();
        RebindState.Attempt attempt = c.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, c.clock.get());
        assertNotNull(attempt);
        c.join(ConnectDecision.Mode.AUTO, attempt).closed(Phase.JOINING, CloseEvent.localResourceAbort(10_000), null);
        assertNotNull(c.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, c.clock.get()),
                "no re-bind failure backoff for a resource close");
    }

    @Test
    void aNonResourceCloseStillAdvancesTheRebindWindow() {
        Client c = new Client();
        RebindState.Attempt attempt = c.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, c.clock.get());
        c.join(ConnectDecision.Mode.AUTO, attempt).closed(Phase.JOINING, CloseEvent.localFailure(), null);
        assertEquals(null, c.rebinds.tryBeginAttempt(HOST, LOGICAL_PORT, c.clock.get()));
        assertFalse(c.cacheAllowsQuic());
    }

    @Test
    void failedKeepsItsNonResourceMeaning() {
        Client c = new Client();
        V3Plan plan = c.join();
        assertTrue(plan.failed(Phase.JOINING, "channel binding failed"));
        assertEquals(CloseClass.NON_RESOURCE, plan.closeClass());
        assertFalse(c.cacheAllowsQuic());
    }
}
