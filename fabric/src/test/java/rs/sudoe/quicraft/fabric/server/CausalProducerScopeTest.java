// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.CausalScope;
import rs.sudoe.quicraft.core.multistream.MultistreamSession.UpdateToken;

/**
 * The synchronous producer-scope frames (U3-W3a): frames are per producer thread, explicitly
 * nested, closed exactly once, and every envelope created while a frame is active snapshots its
 * refs. The envelope keeps its immutable snapshot after the frame closes, and no frame of one
 * producer, thread or connection leaks into another's envelopes.
 */
@SuppressWarnings("try")
class CausalProducerScopeTest {
    private static CausalSendEnvelope envelope(CausalSendContext context) {
        return context.newEnvelope("owner", CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
    }

    private static Thread runOnNewThread(Runnable action) throws InterruptedException {
        Thread thread = new Thread(action, "producer-scope-test");
        thread.start();
        thread.join(10_000L);
        assertFalse(thread.isAlive(), "the helper thread finished");
        return thread;
    }

    @Test
    void nestedScopesLabelEnvelopesOuterToInnerAndRestoreTheOuter() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope outer = tracker.openScope();
        UpdateToken outerToken = tracker.registerUpdate(outer);
        CausalScope inner = tracker.openScope();
        UpdateToken innerToken = tracker.registerUpdate(inner);
        CausalSendContext context = new CausalSendContext("connection");

        try (CausalSendContext.ProducerScope outerScope =
                context.openProducerScope("login_spawn", List.of(outer), List.of(outerToken))) {
            assertEquals("login_spawn", outerScope.name());
            assertEquals(List.of(outer), context.currentProducerSnapshot().scopes());
            CausalSendEnvelope outerEnvelope = envelope(context);
            assertEquals(List.of(outer), outerEnvelope.producer().scopes());
            assertEquals(List.of(outerToken), outerEnvelope.producer().tokens());

            try (CausalSendContext.ProducerScope innerScope =
                    context.openProducerScope("login_ready", List.of(inner), List.of(innerToken))) {
                assertEquals("login_ready", innerScope.name());
                CausalSendEnvelope nested = envelope(context);
                assertEquals(List.of(outer, inner), nested.producer().scopes(),
                        "the envelope names the enclosing and the inner scope, outer first");
                assertEquals(List.of(outerToken, innerToken), nested.producer().tokens());
            }

            CausalSendEnvelope afterInner = envelope(context);
            assertEquals(List.of(outer), afterInner.producer().scopes(),
                    "closing the inner scope restored the outer refs");
            assertEquals(List.of(outerToken), afterInner.producer().tokens());
        }

        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        assertEquals(List.of(), envelope(context).producer().scopes());
    }

    @Test
    void envelopesKeepTheirSnapshotAfterTheScopeCloses() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendContext context = new CausalSendContext("connection");

        CausalSendEnvelope captured;
        try (CausalSendContext.ProducerScope producer =
                context.openProducerScope("login", List.of(scope), List.of(token))) {
            captured = envelope(context);
        }

        assertEquals(List.of(scope), captured.producer().scopes(),
                "closing a frame does not relabel envelopes that already snapped it");
        assertEquals(List.of(token), captured.producer().tokens());
        assertEquals(List.of(), envelope(context).producer().scopes());
    }

    @Test
    void explicitAckAndBatchFactsSurviveTheProducerSnapshot() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendEnvelope.AckOperation ack =
                new CausalSendEnvelope.AckOperation(7, 5, List.of(4, 0, 5, 0));
        CausalSendEnvelope.BatchOperation batch =
                new CausalSendEnvelope.BatchOperation(43, CausalSendEnvelope.BatchKind.INSTALL, 3, 37);
        CausalSendContext context = new CausalSendContext("connection");

        try (CausalSendContext.ProducerScope ignored =
                context.openProducerScope("login", List.of(scope), List.of(token))) {
            CausalSendEnvelope envelope = context.newEnvelope("owner",
                    new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(), ack, batch),
                    CausalSendEnvelope.BundleMetadata.NONE);
            assertEquals(List.of(scope), envelope.producer().scopes(),
                    "active producer refs are added before the explicit refs");
            assertEquals(List.of(token), envelope.producer().tokens());
            assertSame(ack, envelope.producer().ack());
            assertSame(batch, envelope.producer().batch());
        }
    }

    @Test
    void anExceptionalExitClosesBothFrames() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        CausalSendContext context = new CausalSendContext("connection");

        assertThrows(IllegalStateException.class, () -> {
            try (CausalSendContext.ProducerScope outer =
                    context.openProducerScope("login_spawn", List.of(scope), List.of())) {
                try (CausalSendContext.ProducerScope inner =
                        context.openProducerScope("login_ready", List.of(scope), List.of())) {
                    throw new IllegalStateException("injected producer failure");
                }
            }
        });

        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        assertEquals(List.of(), envelope(context).producer().scopes(),
                "an exception must not leave a producer frame behind");
    }

    @Test
    void anOutOfOrderCloseRemovesExactlyItsOwnFrame() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope outer = tracker.openScope();
        CausalScope inner = tracker.openScope();
        CausalSendContext context = new CausalSendContext("connection");

        CausalSendContext.ProducerScope outerScope =
                context.openProducerScope("outer", List.of(outer), List.of());
        CausalSendContext.ProducerScope innerScope =
                context.openProducerScope("inner", List.of(inner), List.of());

        outerScope.close();
        assertEquals(List.of(inner), context.currentProducerSnapshot().scopes(),
                "closing the outer frame early leaves the inner frame active");
        innerScope.close();
        assertEquals(List.of(), context.currentProducerSnapshot().scopes());
        outerScope.close();
        innerScope.close();
        assertEquals(List.of(), context.currentProducerSnapshot().scopes(), "closing twice is a no-op");
    }

    @Test
    void producerFramesArePerThread() throws Exception {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        CausalSendContext context = new CausalSendContext("connection");
        AtomicReference<CausalSendEnvelope.ProducerSnapshot> otherThreadInside = new AtomicReference<>();
        AtomicReference<CausalSendEnvelope.ProducerSnapshot> otherThreadEnvelope = new AtomicReference<>();

        try (CausalSendContext.ProducerScope ignored =
                context.openProducerScope("login_spawn", List.of(scope), List.of())) {
            runOnNewThread(() -> {
                otherThreadInside.set(context.currentProducerSnapshot());
                otherThreadEnvelope.set(envelope(context).producer());
            });

            assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, otherThreadInside.get(),
                    "one producer thread's frame is not visible to another");
            assertEquals(List.of(), otherThreadEnvelope.get().scopes());
            assertEquals(List.of(scope), context.currentProducerSnapshot().scopes(),
                    "the other thread changed nothing on the opening thread");
        }
    }

    @Test
    void aFrameCannotBeClosedFromAnotherThread() throws Exception {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope ref = tracker.openScope();
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendContext.ProducerScope frame =
                context.openProducerScope("login", List.of(ref), List.of());
        AtomicReference<Boolean> closedElsewhere = new AtomicReference<>();

        runOnNewThread(() -> {
            frame.close();
            closedElsewhere.set(context.currentProducerSnapshot().scopes().isEmpty());
        });

        assertTrue(closedElsewhere.get(), "the helper thread sees no frame and closes none");
        assertEquals(List.of(ref), context.currentProducerSnapshot().scopes(),
                "the opening thread's frame is still active");
        frame.close();
        assertEquals(List.of(), context.currentProducerSnapshot().scopes());
    }

    @Test
    void producerFramesDoNotLeakAcrossConnectionsOrSequentialProducers() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope first = tracker.openScope();
        CausalScope second = tracker.openScope();
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendContext otherConnection = new CausalSendContext("other-connection");

        try (CausalSendContext.ProducerScope ignored =
                context.openProducerScope("first", List.of(first), List.of())) {
            assertEquals(List.of(), envelope(otherConnection).producer().scopes(),
                    "a frame labels only envelopes of its own connection");
        }

        try (CausalSendContext.ProducerScope ignored =
                context.openProducerScope("second", List.of(second), List.of())) {
            assertEquals(List.of(second), envelope(context).producer().scopes(),
                    "a closed producer contributes nothing to the next producer's envelopes");
        }

        assertEquals(List.of(), envelope(context).producer().scopes());
    }

    @Test
    void repeatedAndNestedSendsInsideOneProducerAllCarryItsRefs() {
        AckJoinTracker tracker = new AckJoinTracker();
        CausalScope scope = tracker.openScope();
        UpdateToken token = tracker.registerUpdate(scope);
        CausalSendContext context = new CausalSendContext("connection");

        try (CausalSendContext.ProducerScope ignored =
                context.openProducerScope("login", List.of(scope), List.of(token))) {
            CausalSendEnvelope first = envelope(context);
            CausalSendContext.Frame firstFrame = context.enter(first);
            CausalSendEnvelope second = envelope(context);
            CausalSendContext.Frame secondFrame = context.enter(second);
            CausalSendEnvelope third = envelope(context);

            assertEquals(List.of(scope), first.producer().scopes());
            assertEquals(List.of(scope), second.producer().scopes());
            assertEquals(List.of(scope), third.producer().scopes());
            assertEquals(List.of(token), third.producer().tokens());
            assertNotEquals(first.sendId(), second.sendId(), "repeated sends keep distinct identities");

            context.exit(secondFrame);
            context.exit(firstFrame);
            assertEquals(List.of(scope), envelope(context).producer().scopes(),
                    "closing send frames leaves the producer frame active");
        }
    }

    @Test
    void teardownDropsProducerFrames() {
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendContext.ProducerScope scope = context.openProducerScope("login");

        context.teardown();
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot());
        assertEquals(List.of(), envelope(context).producer().scopes());
        scope.close();
        assertEquals(List.of(), context.currentProducerSnapshot().scopes());
    }

    @Test
    void aMissingContextOrFailedOpenLeavesTheVanillaPathUnlabelled() {
        assertNull(CausalSendContext.attached(null));
        assertNull(CausalSendContext.openProducerScope(null, "login_spawn"));

        EmbeddedChannel unattached = new EmbeddedChannel();
        assertNull(CausalSendContext.attached(unattached));
        assertNull(CausalSendContext.openProducerScope(unattached, "login_spawn"));
        unattached.finishAndReleaseAll();

        FailingContext context = new FailingContext("connection");
        context.failOpen = true;
        assertThrows(IllegalStateException.class, () -> context.openProducerScope("login_spawn"));
        assertEquals(CausalSendEnvelope.ProducerSnapshot.EMPTY, context.currentProducerSnapshot(),
                "a failed open publishes no frame");
        assertEquals(List.of(), envelope(context).producer().scopes(),
                "the vanilla path continues with an unlabelled envelope");
    }

    @Test
    void anAttachedChannelOpensAFrameOnItsOwnContext() {
        EmbeddedChannel channel = new EmbeddedChannel();
        CausalSendContext context = new CausalSendContext("connection");
        CausalSendContext.attach(channel, context);

        assertSame(context, CausalSendContext.attached(channel));
        try (CausalSendContext.ProducerScope scope =
                CausalSendContext.openProducerScope(channel, "login_spawn")) {
            assertTrue(scope != null, "the attached context opens the frame");
            assertEquals("login_spawn", scope.name());
            assertEquals(List.of(), context.currentProducerSnapshot().scopes());
        }
        assertNull(CausalSendContext.openProducerScope(new EmbeddedChannel(), "login_spawn"));
        channel.finishAndReleaseAll();
    }

    @Test
    void theLoginProducerMixinsAreRegistered() throws Exception {
        try (InputStream in = CausalProducerScopeTest.class.getResourceAsStream("/quicraft.mixins.json")) {
            assertTrue(in != null, "quicraft.mixins.json is on the test classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"CausalPrepareSpawnMixin\""),
                    "the spawnPlayer producer mixin is listed");
            assertTrue(json.contains("\"CausalReadyMixin\""), "the Ready.spawn producer mixin is listed");
            assertTrue(json.contains("\"CausalPlayerListMixin\""),
                    "the placeNewPlayer producer mixin is listed");
        }
    }

    /** A context whose producer open can fail, to prove a failed attempt publishes no frame. */
    private static final class FailingContext extends CausalSendContext {
        boolean failOpen;

        FailingContext(Object connection) {
            super(connection);
        }

        @Override
        public ProducerScope openProducerScope(String name, List<CausalScope> scopes, List<UpdateToken> tokens) {
            if (failOpen) {
                throw new IllegalStateException("injected producer failure");
            }
            return super.openProducerScope(name, scopes, tokens);
        }
    }
}
