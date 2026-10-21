// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.core.multistream.MultistreamSession;
import rs.sudoe.quicraft.core.transport.V3Connection;

/**
 * The per-connection causal send state (U3-W2a/W3a): the only allocator of send identities, the
 * stored lifecycle epoch, the explicit carrier used to hand one envelope through a pending-send
 * task or an event-loop runnable, and the synchronous producer-scope frames that label the
 * envelopes created while a producer boundary is active (docs/protocol.md 13.19, U3 plan W2/W3).
 *
 * <p><b>Carrying, not looking up.</b> The envelope object itself is captured in the wrapper that
 * the vanilla task or runnable executes. No thread-local, packet identity, side queue or guessed
 * association is consulted. The send frames are a per-producer-thread stack of the sends that are
 * synchronously on the stack right now: it exists so a nested send can restore the outer envelope
 * when it returns, and it is never used to recover a deferred send. The write side reads
 * {@link #currentEnvelope(Channel)}, which sees the envelope the running task or runnable pushed,
 * so it can carry it into encoding without re-deriving ownership.
 *
 * <p><b>Producer scopes.</b> A producer boundary opens an explicitly nested frame on the thread
 * that runs it, and every envelope created while that frame is on its thread's stack snapshots the
 * frame's refs. Nested frames contribute outer to inner, so an envelope inside a nested producer
 * names both the enclosing and the inner scope. Closing removes exactly that frame, whether it
 * exits normally or through an exception, and a closed frame contributes nothing to later
 * envelopes. The frames are synchronous bookkeeping only: a deferred stage receives the envelope
 * object and reads its producer snapshot, never a frame stack.
 *
 * <p><b>Dispatching frames.</b> A logical send is snapshotted at the public {@code send} entry, but
 * the connected path first drains older pending tasks before it reaches its own {@code sendPacket}
 * call. Only a frame whose own vanilla dispatch has started is visible to the write side, so an
 * unrelated action that runs during that drain (for example a {@code runOnceConnected} consumer)
 * cannot inherit the queued send's envelope. The pending path marks its carried frame at once,
 * because its whole vanilla action is the dispatch.
 *
 * <p><b>Fail safe.</b> Every wrapper calls the vanilla action exactly once. If entering, exiting or
 * building a wrapper fails, the wrapper falls back to the vanilla task and the game still sends its
 * packet on the ordinary TCP path.
 *
 * <p><b>Epoch.</b> {@link #setEpoch(long)} stores the value only. This unit does not claim, compute
 * or settle stale work, lifecycle transitions, replacement epochs or multi-producer ordering.
 */
public class CausalSendContext {
    private static final AttributeKey<CausalSendContext> CHANNEL_KEY =
            AttributeKey.valueOf("quicraft.causalSend");

    private final Object connection;
    private final AtomicLong nextSendId = new AtomicLong(1);
    /** Synchronous send frames by producer thread; entries are removed when their stack empties. */
    private final Map<Thread, Deque<Frame>> frames = new ConcurrentHashMap<>();
    /** Synchronous producer-scope frames by producer thread, innermost first on each stack. */
    private final Map<Thread, Deque<ProducerScope>> producerFrames = new ConcurrentHashMap<>();
    /** Guards producer publication, removal, snapshot copying and terminal producer invalidation. */
    private final Object producerMonitor = new Object();
    /** Guarded by producerMonitor; closes producer and wire admission, not send-frame entry. */
    private boolean producersClosed;
    /** One-shot identity bindings, guarded by producerMonitor. Never inferred from packet listeners. */
    private @Nullable V3Connection transport;
    private @Nullable MultistreamSession coreSession;
    private volatile long epoch;

    public CausalSendContext(Object connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** The connection identity every envelope of this context names. */
    public Object connection() {
        return connection;
    }

    /**
     * The lifecycle epoch stored for this connection. Read once per send into the envelope.
     *
     * <p>This is a local admission generation, not the {@code /3} wire epoch: the core sequencer assigns the
     * wire epoch atomically when login (49) or respawn (82) is submitted (docs/protocol.md 13.18), and nothing
     * here is sent. It stays 0; {@code requireAdmission} rejects any other value, so advancing it at a
     * transition would fail envelopes already queued. Reviewed 2026-10-21: no stale wire epoch is reachable.
     */
    public long epoch() {
        return epoch;
    }

    /** Stores the lifecycle epoch. The transition itself belongs to a later unit (W3 lifecycle). */
    public void setEpoch(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        synchronized (producerMonitor) {
            this.epoch = epoch;
        }
    }

    /**
     * Allocates the next connection-local send id and snapshots the given producer facts and bundle
     * metadata into an immutable envelope. Call this once per logical send, before scheduling it.
     *
     * <p>When this producer thread has producer-scope frames open, their refs are placed before the
     * explicitly supplied refs and the explicit ACK/batch facts are retained. With no active frame
     * the supplied snapshot is used unchanged, which is the W2a behaviour.
     */
    public CausalSendEnvelope newEnvelope(@Nullable Object sessionOwner,
            CausalSendEnvelope.ProducerSnapshot producer, CausalSendEnvelope.BundleMetadata bundle) {
        CausalSendEnvelope.ProducerSnapshot explicit = Objects.requireNonNull(producer, "producer");
        synchronized (producerMonitor) {
            return new CausalSendEnvelope(nextSendId.getAndIncrement(), connection, sessionOwner, epoch,
                    withProducerScopes(explicit), Objects.requireNonNull(bundle, "bundle"), this);
        }
    }

    /** Binds the stable transport once; envelopes allocated before this call keep this origin. */
    void bindTransport(V3Connection expected) {
        Objects.requireNonNull(expected, "transport");
        synchronized (producerMonitor) {
            if (producersClosed || (transport != null && transport != expected)) {
                throw new IllegalStateException("terminal or replaced causal transport");
            }
            transport = expected;
        }
    }

    /** Binds the actual activated core session once, before any queued unit is released. */
    void bindSession(V3Connection expected, MultistreamSession session) {
        Objects.requireNonNull(session, "session");
        synchronized (producerMonitor) {
            if (transport != expected || expected.session() != session || producersClosed
                    || (coreSession != null && coreSession != session)) {
                throw new IllegalStateException("terminal or replaced causal session");
            }
            coreSession = session;
        }
    }

    /**
     * Serializes actual synchronous submission with terminal invalidation. The operation must not
     * wait for an event loop. Core assignment is the commit point; reentrant invalidation afterwards
     * throws and requires terminal join cleanup, never replay or an ordinary-send retry.
     */
    void admit(Channel channel, V3Connection expected, MultistreamSession session,
            @Nullable CausalSendEnvelope envelope, Runnable operation) {
        Objects.requireNonNull(session, "session");
        synchronized (producerMonitor) {
            requireAdmission(channel, expected, session, envelope);
            operation.run();
            requireAdmission(channel, expected, session, envelope);
        }
    }

    /** The byte path is allowed only before session binding, with the same origin and no coverage. */
    void replay(Channel channel, V3Connection expected, @Nullable CausalSendEnvelope envelope,
            Runnable operation) {
        synchronized (producerMonitor) {
            requireAdmission(channel, expected, null, envelope);
            if (envelope == null || !envelope.producer().equals(CausalSendEnvelope.ProducerSnapshot.EMPTY)) {
                throw new IllegalStateException("causal coverage cannot use byte replay");
            }
            operation.run();
            requireAdmission(channel, expected, null, envelope);
        }
    }

    /** Caller holds producerMonitor; check terminal last, including after external identity reads. */
    private void requireAdmission(Channel channel, V3Connection expected,
            @Nullable MultistreamSession session, @Nullable CausalSendEnvelope envelope) {
        boolean actualSession = session == null
                ? coreSession == null && expected.session() == null
                : coreSession == session && expected.session() == session;
        if (attached(channel) != this || transport != expected || !actualSession || envelope == null
                || envelope.origin() != this || envelope.connection() != connection
                || envelope.epoch() != epoch || epoch != 0 || producersClosed) {
            throw new IllegalStateException("missing, stale or foreign causal admission authority");
        }
    }

    /**
     * Opens a producer scope on the current thread. The frame contributes no causal refs; use the
     * overload with scopes and update tokens when the producer already holds session handles.
     */
    public ProducerScope openProducerScope(String name) {
        return openProducerScope(name, List.of(), List.of());
    }

    /**
     * Opens a producer scope on the current thread and copies its causal refs. Every envelope
     * created on this thread before the matching close snapshots these refs (outer frames first).
     * The frame is owned by the opening thread and is never visible to another thread's envelopes.
     *
     * @throws IllegalStateException if teardown has permanently closed producer admission
     */
    public ProducerScope openProducerScope(String name, List<MultistreamSession.CausalScope> scopes,
            List<MultistreamSession.UpdateToken> tokens) {
        Objects.requireNonNull(name, "name");
        List<MultistreamSession.CausalScope> scopeCopy = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        List<MultistreamSession.UpdateToken> tokenCopy = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
        ProducerScope scope = new ProducerScope(this, Thread.currentThread(), name, scopeCopy, tokenCopy);
        synchronized (producerMonitor) {
            if (producersClosed) {
                throw new IllegalStateException("producer admission is closed");
            }
            // The terminal check and publication are atomic with respect to teardown.
            producerFrames.computeIfAbsent(scope.thread, ignored -> new ArrayDeque<>()).push(scope);
        }
        return scope;
    }

    /**
     * Closes one producer frame. Only the opening thread may close it, and closing removes exactly
     * that frame even if an exceptional exit closes frames out of nesting order; a second close or a
     * frame from another context or thread is a no-op.
     */
    public void closeProducerScope(@Nullable ProducerScope scope) {
        if (scope == null || scope.owner != this || scope.thread != Thread.currentThread()) {
            return;
        }
        synchronized (producerMonitor) {
            Deque<ProducerScope> stack = producerFrames.get(scope.thread);
            if (stack == null) {
                return;
            }
            stack.remove(scope);
            if (stack.isEmpty()) {
                producerFrames.remove(scope.thread, stack);
            }
        }
    }

    /**
     * The producer refs active on this thread, outer frame first, or {@link
     * CausalSendEnvelope.ProducerSnapshot#EMPTY} outside a producer scope. The snapshot is a copy:
     * closing a frame afterwards does not change it. A snapshot copied before teardown may retain
     * immutable old refs; one copied afterwards cannot read the removed producer frames. This is
     * producer bookkeeping only, not lifecycle settlement or authorization for wire admission.
     */
    public CausalSendEnvelope.ProducerSnapshot currentProducerSnapshot() {
        CausalSendEnvelope.ProducerSnapshot active = activeProducerSnapshot();
        return active == null ? CausalSendEnvelope.ProducerSnapshot.EMPTY : active;
    }

    /** Publishes the context on the connection's channel, or null when no context is attached. */
    public static @Nullable CausalSendContext attached(@Nullable Channel channel) {
        return channel == null ? null : channel.attr(CHANNEL_KEY).get();
    }

    /**
     * Opens a producer scope on the context already attached to {@code channel}, or returns null
     * when the channel carries no context. The caller still opens its own fail-safe boundary around
     * this call; a missing context only means no envelope can be labelled.
     */
    public static @Nullable ProducerScope openProducerScope(@Nullable Channel channel, String name) {
        CausalSendContext context = attached(channel);
        return context == null ? null : context.openProducerScope(name);
    }

    /**
     * Pushes one dispatching envelope on this producer thread's synchronous send stack and returns
     * the frame token that closes exactly that push.
     */
    public Frame enter(CausalSendEnvelope envelope) {
        return enter(envelope, true);
    }

    /**
     * Pushes one envelope on this producer thread's synchronous send stack. A dispatching frame is
     * the one whose vanilla write is being assembled; a non-dispatching frame is a logical send
     * that has only been snapshotted so far (the connected {@code send} between its entry and its
     * own {@code sendPacket} call). Only dispatching frames are visible to the write side.
     */
    public Frame enter(CausalSendEnvelope envelope, boolean dispatching) {
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.connection() != connection) {
            throw new IllegalArgumentException("envelope belongs to another connection");
        }
        Thread thread = Thread.currentThread();
        Frame frame = new Frame(thread, envelope, dispatching);
        frames.computeIfAbsent(thread, ignored -> new ArrayDeque<>()).push(frame);
        return frame;
    }

    /**
     * Closes one frame pushed by {@link #enter}. A frame is closed only on the thread that opened
     * it; a stale frame from before {@link #teardown()} or an out-of-order exit is ignored, never
     * allowed to pop another send's frame.
     */
    public void exit(@Nullable Frame frame) {
        if (frame == null || frame.thread != Thread.currentThread()) {
            return;
        }
        Deque<Frame> stack = frames.get(frame.thread);
        if (stack == null || stack.peek() != frame) {
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            frames.remove(frame.thread, stack);
        }
    }

    /** The send frame currently on this thread's synchronous stack, or null. */
    public @Nullable Frame currentFrame() {
        Deque<Frame> stack = frames.get(Thread.currentThread());
        return stack == null ? null : stack.peek();
    }

    /**
     * The envelope of the write being assembled right now on this thread, or null. This is what the
     * eventual write reads during {@code channel.write}: the carrying task or runnable has pushed a
     * dispatching frame for exactly the duration of the vanilla action.
     */
    public @Nullable CausalSendEnvelope currentEnvelope() {
        Frame frame = currentFrame();
        return frame == null || !frame.dispatching() ? null : frame.envelope();
    }

    /**
     * Wraps a vanilla pending-send task so it carries {@code envelope} explicitly. The wrapper runs
     * the vanilla task exactly once, with a dispatching frame for the whole action. A null or
     * foreign envelope returns the original task unchanged.
     */
    public <T> Consumer<T> carryTask(Consumer<? super T> task, @Nullable CausalSendEnvelope envelope) {
        Objects.requireNonNull(task, "task");
        if (envelope == null || envelope.connection() != connection) {
            return castOriginal(task);
        }
        return value -> {
            Frame frame = enterQuietly(envelope);
            try {
                task.accept(value);
            } finally {
                exitQuietly(frame);
            }
        };
    }

    /**
     * Wraps a vanilla event-loop runnable so it carries {@code envelope} explicitly. The wrapper
     * runs the vanilla runnable exactly once, with a dispatching frame for the whole action. A null
     * or foreign envelope returns the original runnable unchanged.
     */
    public Runnable carryRunnable(Runnable task, @Nullable CausalSendEnvelope envelope) {
        Objects.requireNonNull(task, "task");
        if (envelope == null || envelope.connection() != connection) {
            return task;
        }
        return () -> {
            Frame frame = enterQuietly(envelope);
            try {
                task.run();
            } finally {
                exitQuietly(frame);
            }
        };
    }

    /**
     * Permanently closes producer admission and drops retained producer and synchronous send frames.
     * Producer publication, removal and snapshot copying share this terminal invalidation boundary.
     * Wire admission also closes under this monitor. Previously copied snapshots and carried
     * envelopes remain visible, but cannot be admitted; only core terminal close settles their refs.
     * A deferred task still executes its vanilla action and may re-enter its original envelope.
     * Send-frame entry and exit are not serialized by the producer monitor.
     */
    public void teardown() {
        synchronized (producerMonitor) {
            producersClosed = true;
            frames.clear();
            producerFrames.clear();
        }
    }

    /** Publishes this context on the connection's channel so write-side handlers can read it. */
    public static void attach(Channel channel, CausalSendContext context) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(context, "context");
        channel.attr(CHANNEL_KEY).set(context);
    }

    /**
     * The envelope of the write being assembled right now on this channel's thread, or null. This
     * is the read side the encoder stage uses in W2b; it never falls back to packet identity.
     */
    public static @Nullable CausalSendEnvelope currentEnvelope(@Nullable Channel channel) {
        if (channel == null) {
            return null;
        }
        CausalSendContext context = channel.attr(CHANNEL_KEY).get();
        return context == null ? null : context.currentEnvelope();
    }

    @SuppressWarnings("unchecked")
    private static <T> Consumer<T> castOriginal(Consumer<? super T> task) {
        return (Consumer<T>) task;
    }

    /** The active frames' refs, outer frame first, or null when this thread has no producer frame. */
    private CausalSendEnvelope.@Nullable ProducerSnapshot activeProducerSnapshot() {
        synchronized (producerMonitor) {
            Deque<ProducerScope> stack = producerFrames.get(Thread.currentThread());
            if (stack == null || stack.isEmpty()) {
                return null;
            }
            List<MultistreamSession.CausalScope> scopes = new ArrayList<>();
            List<MultistreamSession.UpdateToken> tokens = new ArrayList<>();
            // push() puts the innermost frame first, so iterate from the bottom for outer-to-inner order.
            for (Iterator<ProducerScope> it = stack.descendingIterator(); it.hasNext();) {
                ProducerScope scope = it.next();
                scopes.addAll(scope.scopes());
                tokens.addAll(scope.tokens());
            }
            return new CausalSendEnvelope.ProducerSnapshot(scopes, tokens, null, null);
        }
    }

    /**
     * Places the active producer refs before the explicit refs and keeps the explicit ACK/batch
     * facts. With no active frame the explicit snapshot itself is returned.
     */
    private CausalSendEnvelope.ProducerSnapshot withProducerScopes(CausalSendEnvelope.ProducerSnapshot explicit) {
        CausalSendEnvelope.ProducerSnapshot active = activeProducerSnapshot();
        if (active == null) {
            return explicit;
        }
        List<MultistreamSession.CausalScope> scopes = new ArrayList<>(active.scopes());
        scopes.addAll(explicit.scopes());
        List<MultistreamSession.UpdateToken> tokens = new ArrayList<>(active.tokens());
        tokens.addAll(explicit.tokens());
        return new CausalSendEnvelope.ProducerSnapshot(scopes, tokens, explicit.ack(), explicit.batch());
    }

    private @Nullable Frame enterQuietly(CausalSendEnvelope envelope) {
        try {
            return enter(envelope);
        } catch (Throwable ignored) {
            // The wrapper still runs the vanilla action; a missing envelope is not a send failure.
            return null;
        }
    }

    private void exitQuietly(@Nullable Frame frame) {
        if (frame == null) {
            return;
        }
        try {
            exit(frame);
        } catch (Throwable ignored) {
            // The vanilla action already ran; a bookkeeping failure must not change the result.
        }
    }

    /**
     * One synchronous producer-scope frame: the immutable refs that every envelope created on the
     * opening thread snapshots while the frame is active. Closing it is idempotent and never changes
     * envelopes that already captured the refs.
     */
    public static final class ProducerScope implements AutoCloseable {
        private final CausalSendContext owner;
        private final Thread thread;
        private final String name;
        private final List<MultistreamSession.CausalScope> scopes;
        private final List<MultistreamSession.UpdateToken> tokens;

        private ProducerScope(CausalSendContext owner, Thread thread, String name,
                List<MultistreamSession.CausalScope> scopes, List<MultistreamSession.UpdateToken> tokens) {
            this.owner = owner;
            this.thread = thread;
            this.name = name;
            this.scopes = scopes;
            this.tokens = tokens;
        }

        /** The producer boundary this frame labels, for diagnostics and later producer waves. */
        public String name() {
            return name;
        }

        /** Immutable causal scopes this frame contributes, in the order the producer supplied them. */
        public List<MultistreamSession.CausalScope> scopes() {
            return scopes;
        }

        /** Immutable update tokens this frame contributes, in the order the producer supplied them. */
        public List<MultistreamSession.UpdateToken> tokens() {
            return tokens;
        }

        /** Closes exactly this frame; a no-op on another thread, a second close, or after teardown. */
        @Override
        public void close() {
            owner.closeProducerScope(this);
        }
    }

    /** One synchronous {@link #enter} push, closed once by {@link #exit(Frame)}. */
    public static final class Frame {
        private final Thread thread;
        private final CausalSendEnvelope envelope;
        private boolean dispatching;

        Frame(Thread thread, CausalSendEnvelope envelope, boolean dispatching) {
            this.thread = thread;
            this.envelope = envelope;
            this.dispatching = dispatching;
        }

        /** The envelope this frame pushed. */
        public CausalSendEnvelope envelope() {
            return envelope;
        }

        /** Whether this frame's own vanilla dispatch has started and is visible to the write side. */
        public boolean dispatching() {
            return dispatching;
        }

        /** Marks this frame's vanilla dispatch as started or finished; owner thread only. */
        public void dispatching(boolean dispatching) {
            this.dispatching = dispatching;
        }
    }
}
