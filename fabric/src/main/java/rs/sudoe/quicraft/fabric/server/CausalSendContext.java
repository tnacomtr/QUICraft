// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The per-connection causal send state (U3-W2a): the only allocator of send identities, the stored
 * lifecycle epoch, and the explicit carrier used to hand one envelope through a pending-send task
 * or an event-loop runnable (docs/protocol.md 13.19, U3 plan W2).
 *
 * <p><b>Carrying, not looking up.</b> The envelope object itself is captured in the wrapper that
 * the vanilla task or runnable executes. No thread-local, packet identity, side queue or guessed
 * association is consulted. The only other state is a per-producer-thread stack of the sends that
 * are synchronously on the stack right now: it exists so a nested send can restore the outer
 * envelope when it returns, and it is never used to recover a deferred send. The write side reads
 * {@link #currentEnvelope(Channel)}, which sees the envelope the running task or runnable pushed,
 * so it can carry it into encoding without re-deriving ownership.
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
    private volatile long epoch;

    public CausalSendContext(Object connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** The connection identity every envelope of this context names. */
    public Object connection() {
        return connection;
    }

    /** The lifecycle epoch stored for this connection. Read once per send into the envelope. */
    public long epoch() {
        return epoch;
    }

    /** Stores the lifecycle epoch. The transition itself belongs to a later unit (W3 lifecycle). */
    public void setEpoch(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
        this.epoch = epoch;
    }

    /**
     * Allocates the next connection-local send id and snapshots the given producer facts and bundle
     * metadata into an immutable envelope. Call this once per logical send, before scheduling it.
     */
    public CausalSendEnvelope newEnvelope(@Nullable Object sessionOwner,
            CausalSendEnvelope.ProducerSnapshot producer, CausalSendEnvelope.BundleMetadata bundle) {
        return new CausalSendEnvelope(nextSendId.getAndIncrement(), connection, sessionOwner, epoch,
                Objects.requireNonNull(producer, "producer"), Objects.requireNonNull(bundle, "bundle"));
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
     * Drops every synchronous frame this connection still holds. A task that runs afterwards finds
     * no envelope and still executes its vanilla action; a later send may enter again.
     */
    public void teardown() {
        frames.clear();
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
