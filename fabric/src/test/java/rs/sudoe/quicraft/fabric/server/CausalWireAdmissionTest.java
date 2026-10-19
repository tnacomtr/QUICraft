// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import rs.sudoe.quicraft.core.multistream.AckJoinTracker;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.transport.V3Connection;

class CausalWireAdmissionTest {
    private static final class Fixture {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final AtomicReference<MultistreamSession> activeSession = new AtomicReference<>();
        final V3Connection transport = (V3Connection) Proxy.newProxyInstance(
                V3Connection.class.getClassLoader(), new Class<?>[] {V3Connection.class},
                (proxy, method, args) -> method.getName().equals("session") ? activeSession.get() : null);
        final Object connectionIdentity = new Object();
        final CausalSendContext context = new CausalSendContext(connectionIdentity);

        Fixture() {
            CausalSendContext.attach(channel, context);
            context.bindTransport(transport);
        }

        CausalSendEnvelope envelope(CausalSendEnvelope.ProducerSnapshot facts) {
            return context.newEnvelope(new Object(), facts, CausalSendEnvelope.BundleMetadata.NONE);
        }
    }

    @Test
    void admissionRequiresAnOriginBoundTransportActualSessionEpochZeroAndLiveContext() {
        Fixture f = new Fixture();
        AckJoinTracker tracker = new AckJoinTracker();
        MultistreamSession.CausalScope scope = tracker.openScope();
        MultistreamSession.UpdateToken token = tracker.registerUpdate(scope);
        CausalSendEnvelope covered = f.envelope(new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null));
        MultistreamSession session = (MultistreamSession) Proxy.newProxyInstance(
                MultistreamSession.class.getClassLoader(), new Class<?>[] {MultistreamSession.class}, (p, m, a) -> null);

        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        assertSame(f.context, covered.origin());
        f.context.admit(f.channel, f.transport, session, covered, () -> { });
        f.channel.finishAndReleaseAll();
    }

    @Test
    void transportAndSessionBindingsAreIdempotentButCannotBeReplaced() {
        Fixture f = new Fixture();
        MultistreamSession first = emptySession();
        MultistreamSession other = emptySession();
        f.context.bindTransport(f.transport);
        f.context.bindTransport(f.transport);
        V3Connection replacementTransport = transportWithSession(first);
        assertThrows(IllegalStateException.class, () -> f.context.bindTransport(replacementTransport));
        f.activeSession.set(first);
        f.context.bindSession(f.transport, first);
        f.context.bindSession(f.transport, first);
        assertThrows(IllegalStateException.class, () -> f.context.bindSession(f.transport, other));
        assertEquals(0, f.context.epoch());
        f.channel.finishAndReleaseAll();
    }

    @Test
    void nullEnvelopeIsRejectedWithoutRunningOperation() {
        Fixture f = new Fixture();
        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        assertRefused(f, f.transport, session, null);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void wrongTransportIsRejectedWithoutRunningOperation() {
        Fixture f = boundFixture();
        V3Connection wrong = transportWithSession(f.activeSession.get());
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        assertRefused(f, wrong, f.activeSession.get(), envelope);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void wrongSessionArgumentIsRejectedWithoutRunningOperation() {
        Fixture f = boundFixture();
        assertRefused(f, f.transport, emptySession(), f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY));
        f.channel.finishAndReleaseAll();
    }

    @Test
    void replacedActualCoreSessionIsRejectedWithoutRunningOperation() {
        Fixture f = boundFixture();
        MultistreamSession bound = f.activeSession.get();
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        f.activeSession.set(emptySession());
        assertRefused(f, f.transport, bound, envelope);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void replacedAttachedContextIsRejectedWithoutRunningOperation() {
        Fixture f = boundFixture();
        MultistreamSession session = f.activeSession.get();
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        CausalSendContext.attach(f.channel, new CausalSendContext(new Object()));
        assertRefused(f, f.transport, session, envelope);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void originlessAndForeignOriginEnvelopesAreRejectedWithoutRunningOperation() {
        Fixture f = boundFixture();
        MultistreamSession session = f.activeSession.get();
        CausalSendEnvelope legacy = new CausalSendEnvelope(1, f.connectionIdentity, null, 0,
                CausalSendEnvelope.ProducerSnapshot.EMPTY, CausalSendEnvelope.BundleMetadata.NONE);
        assertRefused(f, f.transport, session, legacy);

        CausalSendContext foreignOrigin = new CausalSendContext(f.connectionIdentity);
        CausalSendEnvelope foreign = foreignOrigin.newEnvelope(null, CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
        assertRefused(f, f.transport, session, foreign);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void terminalContextAtEpochZeroRejectsWithoutRunningOperation() {
        Fixture f = boundFixture();
        MultistreamSession session = f.activeSession.get();
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        assertEquals(0, f.context.epoch());
        f.context.teardown();
        assertRefused(f, f.transport, session, envelope);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void nonzeroEnvelopeEpochIsRejectedWithoutRunningOperation() {
        Fixture f = new Fixture();
        CausalSendEnvelope oldEpoch = f.context.newEnvelope(null, CausalSendEnvelope.ProducerSnapshot.EMPTY,
                CausalSendEnvelope.BundleMetadata.NONE);
        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        f.context.setEpoch(1);
        assertRefused(f, f.transport, session, oldEpoch);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void envelopeCreatedBeforeTransportAndSessionBindingIsAdmittedAfterOneShotBinding() {
        Fixture f = new Fixture();
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindTransport(f.transport);
        f.context.bindSession(f.transport, session);
        AtomicInteger operations = new AtomicInteger();
        f.context.admit(f.channel, f.transport, session, envelope, operations::incrementAndGet);
        assertEquals(1, operations.get());
        f.channel.finishAndReleaseAll();
    }

    @Test
    void emptyCoverageReplayWorksBeforeSessionBindingButFailsAfterBinding() {
        Fixture f = new Fixture();
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        AtomicInteger operations = new AtomicInteger();
        f.context.replay(f.channel, f.transport, envelope, operations::incrementAndGet);
        assertEquals(1, operations.get());

        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        assertReplayRefused(f, envelope);
        assertEquals(1, operations.get());
        f.channel.finishAndReleaseAll();
    }

    @Test
    void coveredEnvelopeCannotUseByteReplay() {
        Fixture f = new Fixture();
        AckJoinTracker tracker = new AckJoinTracker();
        MultistreamSession.CausalScope scope = tracker.openScope();
        MultistreamSession.UpdateToken token = tracker.registerUpdate(scope);
        CausalSendEnvelope covered = f.envelope(
                new CausalSendEnvelope.ProducerSnapshot(List.of(), List.of(token), null, null));
        assertReplayRefused(f, covered);
        f.channel.finishAndReleaseAll();
    }

    @Test
    void invalidationOnAnotherThreadCannotInterleaveWithAdmittedOperation() throws Exception {
        Fixture f = new Fixture();
        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch invalidated = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread submit = new Thread(() -> {
            try {
                f.context.admit(f.channel, f.transport, session, envelope, () -> {
                    inside.countDown();
                    await(release);
                });
            } catch (Throwable t) { failure.set(t); }
        }, "admission-submit");
        Thread teardown = new Thread(() -> {
            try { await(inside); invalidated.countDown(); f.context.teardown(); }
            catch (Throwable t) { failure.set(t); }
        }, "admission-invalidate");
        submit.start();
        teardown.start();
        try {
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            assertTrue(invalidated.await(10, TimeUnit.SECONDS), "teardown thread reached the invalidation attempt");
            assertTrue(awaitBlockedOrFinished(teardown),
                    "teardown must block on the monitor until the admitted operation releases it");
            assertTrue(teardown.isAlive(), "teardown cannot finish while admission holds the monitor");
            release.countDown();
            assertTrue(awaitLatch(invalidated, 10, TimeUnit.SECONDS));
            submit.join(10_000);
            teardown.join(10_000);
            assertFalse(submit.isAlive());
            assertFalse(teardown.isAlive());
            assertEquals(null, failure.get());
        } finally {
            release.countDown();
            submit.join(10_000);
            teardown.join(10_000);
            f.channel.finishAndReleaseAll();
        }
    }

    @Test
    void reentrantTeardownDuringSendFailsThePostOperationAuthorityCheck() {
        Fixture f = new Fixture();
        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        CausalSendEnvelope envelope = f.envelope(CausalSendEnvelope.ProducerSnapshot.EMPTY);
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        assertThrows(IllegalStateException.class, () -> f.context.admit(f.channel, f.transport, session, envelope, () -> {
            reached.set(true);
            f.context.teardown();
        }));
        assertEquals(Boolean.TRUE, reached.get());
        f.channel.finishAndReleaseAll();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static boolean awaitBlockedOrFinished(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED || !thread.isAlive()) {
                return thread.getState() == Thread.State.BLOCKED;
            }
            Thread.yield();
        }
        return thread.getState() == Thread.State.BLOCKED;
    }

    private static boolean awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static Fixture boundFixture() {
        Fixture f = new Fixture();
        MultistreamSession session = emptySession();
        f.activeSession.set(session);
        f.context.bindSession(f.transport, session);
        return f;
    }

    private static void assertRefused(Fixture f, V3Connection transport, MultistreamSession session,
            CausalSendEnvelope envelope) {
        AtomicInteger operations = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> f.context.admit(f.channel, transport, session, envelope, operations::incrementAndGet));
        assertEquals(0, operations.get(), "rejected admission must not run its operation");
    }

    private static void assertReplayRefused(Fixture f, CausalSendEnvelope envelope) {
        AtomicInteger operations = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> f.context.replay(f.channel, f.transport, envelope, operations::incrementAndGet));
        assertEquals(0, operations.get(), "rejected replay must not run its operation");
    }

    private static V3Connection transportWithSession(MultistreamSession session) {
        AtomicReference<MultistreamSession> active = new AtomicReference<>(session);
        return (V3Connection) Proxy.newProxyInstance(V3Connection.class.getClassLoader(),
                new Class<?>[] {V3Connection.class},
                (proxy, method, args) -> method.getName().equals("session") ? active.get() : null);
    }

    private static MultistreamSession emptySession() {
        return (MultistreamSession) Proxy.newProxyInstance(MultistreamSession.class.getClassLoader(),
                new Class<?>[] {MultistreamSession.class}, (proxy, method, args) -> null);
    }
}
