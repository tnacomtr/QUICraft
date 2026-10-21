// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;
import rs.sudoe.quicraft.core.time.StallClock;

/**
 * Serial delivery driver for serverbound receive (protocol.md 13.10, 13.12, 13.18), the sibling of
 * {@link SessionReceiveDriver}.
 *
 * <p>It takes CONTROL and REALTIME units from a {@link SessionReadPump}. The {@link StreamReceiver} is
 * serverbound and owns its own slots: a unit is charged there when it is admitted and returned when it is
 * applied. The pump unit is closed only after the game's acknowledgement, which returns its partition
 * charges. Eligibility comes before inflation: a head whose prerequisites are not applied is never inflated.
 * An eligible head is then inflated, decoded, route-validated before enqueue, and handed to the game. One
 * execution unit at a time (13.12). A kind 1 END is applied as a global fence and closes with 0x0 unless a
 * violation wins.
 *
 * <p>Every terminal path runs one cleanup: the pump is cancelled (which closes every held unit), every
 * workspace byte the driver holds is returned once, and only then the sink is told the close. {@link #close()}
 * runs the same cleanup and does not call the sink.
 *
 * <p>Serverbound bundles are refused by the pump (13.15), so this driver handles single records only. A
 * decoder that cannot fit its output in the execution workspace closes 0x12: with one unit executing, no
 * other holder can free workspace for a retry, so the refusal is final.
 *
 * <p>Thread confinement: not thread-safe; every call runs on the one receive thread.
 */
public final class ServerReceiveDriver {
    private static final byte[] NO_REASON = new byte[0];
    private static final ReceiveRoute END_ROUTE =
            new ReceiveRoute(ReceiveRoute.Lane.CONTROL, Dependency.GLOBAL, null, false, false);

    /** Records the driver held for one admitted unit. */
    private static final class Entry {
        final SessionReadPump.Unit unit;
        final int stream;
        final long sequence;
        final long wireBytes;
        boolean enqueued;

        Entry(SessionReadPump.Unit unit) {
            this.unit = unit;
            this.stream = unit.stream();
            this.sequence = unit.record().sequence();
            this.wireBytes = unit.wireBytes();
        }
    }

    private final SessionReadPump pump;
    private final StreamReceiver receiver;
    private final SessionReceiveDriver.RouteSource routes;
    private final int protocolNumber;
    private final LaneGame game;
    private final SessionReceiveDriver.CloseSink sink;
    private final Entry[] held;
    private final List<SessionReadPump.Unit> waiting = new ArrayList<SessionReadPump.Unit>();
    /** The unit enqueued and not yet acknowledged; at most one (13.12). Null when none is executing. */
    private Entry executing;
    /** Workspace bytes this driver has reserved and not yet returned. Terminal cleanup returns them once. */
    private long reservedWorkspace;
    private boolean closed;

    /**
     * Legacy boundary mode: the blocked-head timer is driven by {@link #stallStarted(long)} and
     * {@link #stallEnded(long)} notifications on {@code clock}.
     *
     * @param chunks negotiated chunk count N, 1..5; the receiver keeps the N+3 stream indices
     * @param pump the serverbound read pump for the same session
     * @param validator injected shard, fence and join checks
     * @param routes route lookup for the connection's protocol
     * @param protocolNumber the negotiated protocol number, for route keys
     * @param game the game side: decode, deliver, acknowledgements
     * @param clock the monotonic clock for the blocked-head timer
     * @param sink receives the one close
     */
    public ServerReceiveDriver(int chunks, SessionReadPump pump, StreamReceiver.Validator validator,
                               SessionReceiveDriver.RouteSource routes, int protocolNumber, LaneGame game,
                               LongSupplier clock, SessionReceiveDriver.CloseSink sink) {
        if (pump == null || validator == null || routes == null || game == null || clock == null || sink == null) {
            throw new IllegalArgumentException("driver arguments are required");
        }
        this.pump = pump;
        this.receiver = new StreamReceiver(chunks, Direction.SERVERBOUND, validator, clock);
        this.routes = routes;
        this.protocolNumber = protocolNumber;
        this.game = game;
        this.sink = sink;
        this.held = new Entry[chunks + 3];
    }

    /**
     * Cumulative mode: the receiver's blocked-head timer reads {@code source} (13.10), so a late
     * observation excludes exactly what a timely one would have. {@code clock} stays the session's
     * non-excluding transport clock, kept for deadlines outside stall accounting; it must not be null.
     * Boundary stall notifications forwarded through this driver throw in this mode.
     *
     * @param chunks negotiated chunk count N, 1..5; the receiver keeps the N+3 stream indices
     * @param pump the serverbound read pump for the same session
     * @param validator injected shard, fence and join checks
     * @param routes route lookup for the connection's protocol
     * @param protocolNumber the negotiated protocol number, for route keys
     * @param game the game side: decode, deliver, acknowledgements
     * @param clock the non-excluding transport clock
     * @param sink receives the one close
     * @param source the stall accounting source of the executor incarnation, never replaced; not null
     */
    public ServerReceiveDriver(int chunks, SessionReadPump pump, StreamReceiver.Validator validator,
                               SessionReceiveDriver.RouteSource routes, int protocolNumber, LaneGame game,
                               LongSupplier clock, SessionReceiveDriver.CloseSink sink, StallClock source) {
        if (pump == null || validator == null || routes == null || game == null || clock == null || sink == null
                || source == null) {
            throw new IllegalArgumentException("driver arguments and the stall source are required");
        }
        this.pump = pump;
        this.receiver = new StreamReceiver(chunks, Direction.SERVERBOUND, validator, source);
        this.routes = routes;
        this.protocolNumber = protocolNumber;
        this.game = game;
        this.sink = sink;
        this.held = new Entry[chunks + 3];
    }

    /** Returns true once the driver has closed the connection. */
    public boolean closed() {
        return closed;
    }

    /** Returns the receiver, for the integration and for tests. */
    public StreamReceiver receiver() {
        return receiver;
    }

    /** Forwards the start of a game-executor tick stall, timestamped at {@code atNanos} (13.10). */
    public void stallStarted(long atNanos) {
        receiver.stallStarted(atNanos);
    }

    /** Forwards the end of a game-executor tick stall, timestamped at {@code atNanos} (13.10). */
    public void stallEnded(long atNanos) {
        receiver.stallEnded(atNanos);
    }

    /**
     * Reads at most one step from the pump, admits what completed and delivers the eligible head when no unit
     * is executing.
     *
     * @throws IOException when the byte source fails
     */
    public void receive() throws IOException {
        if (closed) return;
        try {
            waiting.addAll(pump.step());
        } catch (MultistreamProtocolException e) {
            fail(e.closeCode(), NO_REASON);
            return;
        }
        drive();
    }

    /**
     * The game's serial handler applied the executing unit whose sequence is {@code sequence} on {@code stream}
     * (D). Releases the receiver's slot and the workspace, polls the violation before anything else runs, and
     * closes the pump unit only when it passes.
     */
    public void acknowledged(int stream, long sequence) {
        if (closed) return;
        Entry entry = held[stream];
        if (entry == null || entry != executing || entry.sequence != sequence) {
            throw new IllegalStateException("acknowledgement for an unexpected unit on stream " + stream);
        }
        receiver.applied(stream, entry.sequence);
        executing = null;
        releaseCharge(reservedWorkspace);
        // A lapsed wait or an unavailable source leaves the head and its slot retained by the receiver. Keep held[]
        // set until the check passes: the terminal cleanup then releases that slot, which it can only do for a held entry.
        ReceiveClose violation = receiver.pendingViolation();
        if (violation != null) {
            fail(violation);
            return;
        }
        held[stream] = null;
        entry.unit.close();
        game.onApplicationComplete(laneOf(stream), stream, sequence);
        drive();
    }

    /**
     * Closes the driver from outside the receive path. Runs the same terminal cleanup as a protocol close, once,
     * and does not call the sink.
     */
    public void close() {
        if (closed) return;
        terminate();
    }

    /** Admits waiting units, then enqueues the next eligible head while no unit is executing. */
    private void drive() {
        while (!closed) {
            if (!admitWaiting()) return;
            ReceiveClose violation = receiver.pendingViolation();
            if (violation != null) {
                fail(violation);
                return;
            }
            ReceiveClose expired = receiver.expiredWait();
            if (expired != null) {
                fail(expired);
                return;
            }
            if (executing != null) return; // one execution unit at a time (13.12)
            int stream = receiver.nextToEnqueue();
            if (stream < 0) return;
            if (!enqueue(stream)) return;
        }
    }

    /** Offers every waiting unit to the receiver; admitted units become the stream's held head. */
    private boolean admitWaiting() {
        Iterator<SessionReadPump.Unit> it = waiting.iterator();
        while (it.hasNext()) {
            SessionReadPump.Unit unit = it.next();
            Entry entry = new Entry(unit);
            StreamReceiver.Admission admission = receiver.offer(unit.record(), unit.record().sequence(),
                    unit.wireBytes(), 1);
            if (admission.kind() == StreamReceiver.Admission.Kind.VIOLATION) {
                fail(admission.close());
                return false;
            }
            if (admission.kind() == StreamReceiver.Admission.Kind.ADMITTED) {
                held[entry.stream] = entry;
                it.remove();
            }
        }
        return !closed;
    }

    /**
     * Inflates, decodes, validates and enqueues the eligible head of {@code stream}. Inflation happens here,
     * after eligibility, so a blocked head is never inflated. Returns false when the driver closed.
     */
    private boolean enqueue(int stream) {
        Entry entry = held[stream];
        if (entry.unit.record().kind() == RecordCodec.KIND_END) {
            return endFence(stream, entry);
        }
        Lane lane = laneOf(stream);
        if (!charge(entry.wireBytes) || !charge(entry.unit.inflationBytes())) {
            fail(ReceiveClose.resourceWait("execution workspace"));
            return false;
        }
        byte[] content;
        try {
            content = pump.inflate(entry.unit);
        } catch (MultistreamProtocolException e) {
            fail(e.closeCode(), NO_REASON);
            return false;
        }
        Attempt attempt = new Attempt();
        long before = attempt.reserved;
        LaneGame.Decoded decoded;
        try {
            decoded = game.decodeInflated(lane, entry.sequence, content, false, attempt);
        } catch (RetryLater e) {
            fail(ReceiveClose.resourceWait("execution workspace"));
            return false;
        } catch (StallClock.UnavailableException e) {
            throw e;
        } catch (Exception e) {
            fail(ReceiveClose.peerViolation("decode failed: " + e));
            return false;
        }
        if (decoded == null) {
            fail(ReceiveClose.peerViolation("decoder returned nothing"));
            return false;
        }
        if (decoded.bundle() != Envelope.Bundle.NONE) {
            fail(ReceiveClose.peerViolation("bundle delimiter on a serverbound record (13.15)"));
            return false;
        }
        if (attempt.reserved == before && decoded.retainedBytes() > 0 && !charge(decoded.retainedBytes())) {
            fail(ReceiveClose.resourceWait("decoded output"));
            return false;
        }
        if (decoded.uncompressed() != null) {
            game.onDecodedUncompressed(lane, stream, entry.sequence, decoded.uncompressed());
        }
        ReceiveRoute route = routeOf(decoded);
        ReceiveClose validated = receiver.validateEligible(stream, route);
        if (validated != null) {
            fail(validated);
            return false;
        }
        receiver.enqueued(stream, entry.sequence);
        // From Q until D the peer has done its part. Any delay (including an acceptance
        // backlog or a protocol swap) is ours, not an unseen prerequisite at the peer.
        // StreamReceiver.applied clears this mark, including transitive dependent heads.
        receiver.markLocallyWithheld(stream, entry.sequence);
        entry.enqueued = true;
        executing = entry;
        List<Object> packets = new ArrayList<Object>(1);
        packets.add(decoded.packet());
        game.onEnqueued(lane, stream, entry.sequence);
        game.deliver(lane, entry.sequence, packets, false, decoded.uncompressedBytes());
        return true;
    }

    /** Applies an eligible END as a global fence: validated, applied, never decoded or delivered. */
    private boolean endFence(int stream, Entry entry) {
        ReceiveClose validated = receiver.validateEligible(stream, END_ROUTE);
        if (validated != null) {
            fail(validated);
            return false;
        }
        receiver.enqueued(stream, entry.sequence);
        entry.enqueued = true;
        receiver.applied(stream, entry.sequence);
        held[stream] = null;
        ReceiveClose violation = receiver.pendingViolation();
        if (violation != null) {
            fail(violation);
            return false;
        }
        fail(0, NO_REASON);
        return false;
    }

    /** Builds the route of one decoded serverbound record (13.6). An unknown packet id is a global fence (13.5). */
    private ReceiveRoute routeOf(LaneGame.Decoded decoded) {
        RouteV2 v2 = routes.routeFor(new RouteKey(protocolNumber, decoded.state(), Direction.SERVERBOUND,
                decoded.packetId()));
        if (v2 == null) {
            return new ReceiveRoute(ReceiveRoute.Lane.CONTROL, Dependency.GLOBAL, null, false, false);
        }
        ReceiveRoute.Lane routeLane;
        switch (v2.lane) {
            case CONTROL:
                routeLane = ReceiveRoute.Lane.CONTROL;
                break;
            case REALTIME:
                routeLane = ReceiveRoute.Lane.REALTIME;
                break;
            default:
                routeLane = ReceiveRoute.Lane.CHUNK;
                break;
        }
        boolean join = v2.dependency == Dependency.ACK_JOIN || v2.dependency == Dependency.BATCH_JOIN;
        return new ReceiveRoute(routeLane, v2.dependency, null, v2.epochChange, join);
    }

    private static Lane laneOf(int stream) {
        if (stream == LogicalStream.CONTROL) return Lane.CONTROL;
        if (stream == LogicalStream.REALTIME) return Lane.REALTIME;
        return Lane.WORLD;
    }

    /** Reserves driver workspace; false when the workspace cannot carry it (nothing is reserved then). */
    private boolean charge(long bytes) {
        if (!receiver.reserveWorkspace(bytes)) return false;
        reservedWorkspace += bytes;
        return true;
    }

    /** Returns driver workspace. Callers pass only bytes this driver reserved. */
    private void releaseCharge(long bytes) {
        if (bytes == 0) return;
        receiver.releaseWorkspace(bytes);
        reservedWorkspace -= bytes;
    }

    /** The reservation handed to the game for one decode: charges come from the driver's workspace. */
    private final class Attempt implements LaneGame.Reservation {
        /** Bytes the game reserved during this decode. */
        long reserved;

        @Override
        public void reserve(long additionalRetainedBytes, int totalUncompressedBytes) {
            if (additionalRetainedBytes < 0 || !charge(additionalRetainedBytes)) {
                throw new RetryLater();
            }
            reserved += additionalRetainedBytes;
        }
    }

    /** Thrown from {@link Attempt#reserve} when the workspace cannot carry the output (13.12). */
    private static final class RetryLater extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RetryLater() {
            super("execution workspace is full", null, false, false);
        }
    }

    private void fail(ReceiveClose close) {
        fail(close.errorCode(), closeReason(close));
    }

    private void fail(int errorCode, byte[] reason) {
        if (closed) return;
        terminate();
        sink.close(errorCode, reason);
    }

    /**
     * The one terminal cleanup: cancels the pump (which closes every held unit and releases its charges), returns
     * the receiver's slots of every admitted, unapplied unit, returns the driver's workspace once and drops the
     * held and waiting units. Sets {@code closed} first, so it runs once.
     */
    private void terminate() {
        closed = true;
        executing = null;
        ReceiveCleanup.run(pump::cancel, this::releaseHeldSlots, receiver::discard,
                () -> releaseCharge(reservedWorkspace), waiting::clear, () -> Arrays.fill(held, null));
    }

    private void releaseHeldSlots() {
        Runnable[] releases = new Runnable[held.length];
        for (int stream = 0; stream < held.length; stream++) {
            final int index = stream;
            releases[stream] = () -> {
                if (held[index] != null && receiver.slots().holdsHead(index)) {
                    receiver.slots().releaseHead(index);
                }
            };
        }
        ReceiveCleanup.run(releases);
    }

    private static byte[] closeReason(ReceiveClose close) {
        if (close.errorCode() == ReceiveClose.RESOURCE) {
            byte[] classified = close.reason();
            return classified != null ? classified : SessionReceiveDriver.GENERIC_RESOURCE_REASON.clone();
        }
        return NO_REASON;
    }
}
