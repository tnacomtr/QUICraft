// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import rs.sudoe.quicraft.core.routing.Dependency;
import rs.sudoe.quicraft.core.routing.Direction;
import rs.sudoe.quicraft.core.routing.Extractor;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.RouteKey;
import rs.sudoe.quicraft.core.routing.RouteV2;

/**
 * Serial delivery driver for clientbound receive (protocol.md 13.10, 13.12, 13.15, 13.16, 13.18).
 *
 * <p>It takes whole units from a {@link ClientReadPump}, admits them to a {@link StreamReceiver} that
 * shares the pump's slots (the pump is the single owner of the head charge), decodes the eligible head
 * through {@link LaneGame}, validates the route before enqueue, hands one atomic unit to the game and
 * completes it on acknowledgement. Queued (Q) releases nothing; only applied (D) releases the workspace,
 * the slot and the dependencies.
 *
 * <p>One execution unit at a time (13.12): once a unit is enqueued, no other head is decoded or delivered
 * until its acknowledgement releases the workspace. A kind 1 END is a CONTROL global fence: it is validated
 * and applied here, never decoded or delivered, and a clean shutdown (0x0) follows unless a violation wins.
 *
 * <p>Every terminal path goes through one cleanup: the pump is cancelled, every workspace byte the driver
 * still holds is returned once, the held and waiting units are dropped, and only then is the sink told
 * the close. {@link #close()} runs the same cleanup for an external close and does not call the sink.
 *
 * <p>Bundle scope (13.15, with the orchestrator's mapping): GLOBAL and FENCE map to global, VIEW to view,
 * INTERACTION to interaction, CONTROL to control, ORDINARY to ordinary. Order: global beats everything;
 * view and interaction are incomparable, so holding both promotes to global; otherwise the highest of
 * view, interaction, control; the floor is control. LANE and any unknown dependency promote to global,
 * as does a CHUNK member. ACK_JOIN and BATCH_JOIN members, epoch changes and state changes in a bundle
 * close 0x11.
 *
 * <p>Missing-close timer (13.15): a bundle opener whose unit is not complete 10 s after admission closes
 * 0x11, on the injected non-excluding clock.
 *
 * <p>Thread confinement: this class is not thread-safe. Every call (receive, acknowledged, close, stall and
 * withholding notices) must run on the one receive thread. The game's acknowledgements from other threads
 * are marshalled onto that thread by the integration (U8f).
 */
public final class SessionReceiveDriver {
    /** Missing-close limit for a bundle opener, 13.15. */
    public static final long BUNDLE_CLOSE_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** The bounded generic 0x12 phrase used when no classified reason is present (orchestrator decision). */
    static final byte[] GENERIC_RESOURCE_REASON = "resource".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] NO_REASON = new byte[0];
    /** The fixed route of a CONTROL global fence, used for END (13.4: END is CONTROL/global only). */
    private static final ReceiveRoute END_ROUTE =
            new ReceiveRoute(ReceiveRoute.Lane.CONTROL, Dependency.GLOBAL, null, false, false);

    /** Maps a route key to its reviewed route; production passes {@code routingTable::routeV2For}. */
    public interface RouteSource {
        /** Returns the schema-2 route, or null for an unknown packet id (13.5: a global fence). */
        RouteV2 routeFor(RouteKey key);
    }

    /** Receives the single connection close the driver decides on: an error code and its reason bytes. */
    public interface CloseSink {
        void close(int errorCode, byte[] reason);
    }

    /** Records the driver held for one admitted unit. */
    private static final class Entry {
        final ClientReadPump.Unit unit;
        final int stream;
        final long openerSeq;
        final long closingSeq;
        final long wireBytes;
        boolean enqueued;

        Entry(ClientReadPump.Unit unit) {
            this.unit = unit;
            this.stream = unit.stream();
            this.openerSeq = unit.record().sequence();
            this.closingSeq = unit.closingSequence();
            this.wireBytes = unit.wireBytes();
        }
    }

    private final ClientReadPump pump;
    private final StreamReceiver.Validator validator;
    private final StreamReceiver receiver;
    private final RouteSource routes;
    private final int protocolNumber;
    private final Map<String, Extractor> extractors;
    private final LaneGame game;
    private final LongSupplier clock;
    private final CloseSink sink;
    private final int streams;
    private final Entry[] held;
    private final List<ClientReadPump.Unit> waiting = new ArrayList<ClientReadPump.Unit>();
    /** The unit enqueued and not yet acknowledged; at most one (13.12). Null when none is executing. */
    private Entry executing;
    /** Workspace bytes this driver has reserved and not yet returned. Terminal cleanup returns them once. */
    private long reservedWorkspace;
    private boolean closed;

    /**
     * @param chunks negotiated chunk count N, 1..6, equal to the pump's
     * @param pump the clientbound read pump; it charges and commits the slots
     * @param slots the pump's slots; the receiver shares them and never charges again
     * @param validator injected shard, fence and join checks, also run on each bundle member
     * @param routes route lookup for the connection's protocol
     * @param protocolNumber the negotiated protocol number, for route keys
     * @param extractors reviewed extractors by name, for CHUNK positions
     * @param game the game side: decode, deliver, acknowledgements
     * @param clock the same non-excluding clock the pump was built with
     * @param sink receives the one close
     */
    public SessionReceiveDriver(int chunks, ClientReadPump pump, ReceiveSlots slots,
                                StreamReceiver.Validator validator, RouteSource routes, int protocolNumber,
                                Map<String, Extractor> extractors, LaneGame game, LongSupplier clock,
                                CloseSink sink) {
        if (pump == null || slots == null || validator == null || routes == null || extractors == null
                || game == null || clock == null || sink == null) {
            throw new IllegalArgumentException("driver arguments are required");
        }
        this.pump = pump;
        this.validator = validator;
        this.receiver = new StreamReceiver(chunks, slots, validator, clock);
        this.routes = routes;
        this.protocolNumber = protocolNumber;
        this.extractors = extractors;
        this.game = game;
        this.clock = clock;
        this.sink = sink;
        this.streams = chunks + 2;
        this.held = new Entry[streams];
    }

    /** Returns true once the driver has closed the connection, by a violation, by END or by {@link #close()}. */
    public boolean closed() {
        return closed;
    }

    /** Returns the receiver, for the integration and for tests. */
    public StreamReceiver receiver() {
        return receiver;
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
     * The game's serial handler applied the enqueued unit whose closing sequence is {@code closingSequence}
     * on {@code stream} (D). Call on the receive thread only. Polls the violation after the apply, before any
     * further decode or delivery, and releases the workspace and the pump unit only when it passes.
     */
    public void acknowledged(int stream, long closingSequence) {
        if (closed) return;
        Entry entry = held[stream];
        if (entry == null || entry != executing || entry.closingSeq != closingSequence) {
            throw new IllegalStateException("acknowledgement for an unexpected unit on stream " + stream);
        }
        receiver.applied(stream, entry.openerSeq);
        held[stream] = null;
        executing = null;
        releaseCharge(reservedWorkspace);
        ReceiveClose violation = receiver.pendingViolation();
        if (violation != null) {
            fail(violation);
            return;
        }
        pump.commit(entry.unit);
        game.onApplicationComplete(laneOf(stream), stream, closingSequence);
        drive();
    }

    /**
     * Closes the driver from outside the receive path (the connection went away). Runs the same terminal
     * cleanup as a protocol close, once; the sink is not called because the connection is already closing.
     * A second call, or a call after a close, does nothing.
     */
    public void close() {
        if (closed) return;
        terminate();
    }

    /** Forwards the start of a game-executor tick stall to the receiver. */
    public void stallStarted() {
        receiver.stallStarted();
    }

    /** Forwards the end of a game-executor tick stall to the receiver. */
    public void stallEnded() {
        receiver.stallEnded();
    }

    /**
     * Records a locally withheld prerequisite. A sequence inside a held bundle is normalised to its
     * opener, so the bundle's withholding is accounted to the one opener (13.12, 13.15).
     */
    public void markWithheld(int stream, long sequence) {
        Entry entry = held[stream];
        long key = sequence;
        if (entry != null && sequence >= entry.openerSeq && sequence <= entry.closingSeq) {
            key = entry.openerSeq;
        }
        receiver.markLocallyWithheld(stream, key);
    }

    /**
     * Admits waiting units, then enqueues the next eligible head while no unit is executing. Stops when the
     * driver closes, when nothing is eligible, or when a head must wait for the workspace.
     */
    private void drive() {
        while (!closed) {
            if (!admitWaiting()) return;
            ReceiveClose violation = receiver.pendingViolation();
            if (violation != null) {
                fail(violation);
                return;
            }
            long oldest = pump.oldestPendingOpenerNanos();
            if (oldest >= 0 && clock.getAsLong() - oldest > BUNDLE_CLOSE_NANOS) {
                fail(ReceiveClose.peerViolation("bundle close missing after 10 s (13.15)"));
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

    /** Offers every waiting unit whose stream is free. Returns false when the driver closed. */
    private boolean admitWaiting() {
        Iterator<ClientReadPump.Unit> it = waiting.iterator();
        while (it.hasNext()) {
            ClientReadPump.Unit unit = it.next();
            Entry entry = new Entry(unit);
            StreamReceiver.Admission admission = receiver.offer(unit.record(), unit.closingSequence(),
                    unit.wireBytes(), unit.recordCount());
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
     * Decodes, validates and enqueues the eligible head of {@code stream}. Returns false when the driver
     * closed or must wait for the workspace; the caller stops driving in both cases.
     */
    private boolean enqueue(int stream) {
        Entry entry = held[stream];
        if (entry.unit.recordCount() == 1 && entry.unit.record().kind() == RecordCodec.KIND_END) {
            return endFence(stream, entry);
        }
        Lane lane = laneOf(stream);
        if (!charge(entry.wireBytes)) {
            fail(ReceiveClose.resourceWait("execution workspace"));
            return false;
        }
        Attempt attempt = new Attempt();
        List<Object> packets = new ArrayList<Object>(entry.unit.recordCount());
        List<ReceiveRoute> memberRoutes = new ArrayList<ReceiveRoute>(entry.unit.recordCount());
        boolean bundle = entry.unit.recordCount() > 1;
        List<rs.sudoe.quicraft.core.multistream.Record> records = entry.unit.records();
        for (int i = 0; i < records.size(); i++) {
            rs.sudoe.quicraft.core.multistream.Record record = records.get(i);
            boolean inside = i > 0;
            LaneGame.Decoded decoded;
            long before = attempt.reserved;
            try {
                decoded = game.decode(lane, record.sequence(), record.payload(), inside, attempt);
            } catch (RetryLater retry) {
                return retryLater();
            } catch (Exception e) {
                fail(ReceiveClose.peerViolation("decode failed: " + e));
                return false;
            }
            if (decoded == null) {
                fail(ReceiveClose.peerViolation("decoder returned nothing"));
                return false;
            }
            if (attempt.reserved == before && decoded.retainedBytes() > 0) {
                // A game that did not reserve gets its retained bytes charged after the fact (13.12).
                if (!charge(decoded.retainedBytes())) {
                    fail(ReceiveClose.resourceWait("decoded output"));
                    return false;
                }
            }
            ReceiveClose markerClose = checkDelimiter(decoded.bundle(), i, records.size(), bundle);
            if (markerClose == null && bundle && decoded.stateChange()) {
                markerClose = ReceiveClose.peerViolation("state change inside a bundle (13.15)");
            }
            if (markerClose != null) {
                fail(markerClose);
                return false;
            }
            if (decoded.uncompressed() != null) {
                game.onDecodedUncompressed(lane, stream, record.sequence(), decoded.uncompressed());
            }
            ReceiveRoute route = routeOf(decoded);
            if (route == null) {
                fail(ReceiveClose.peerViolation("no usable route for packet " + decoded.packetId()));
                return false;
            }
            if (bundle) {
                // Members are not the opener: validateEligible checks only the opener's body, so each member is checked here.
                if (!validator.valid(stream, record, route)) {
                    fail(ReceiveClose.peerViolation("member validation failed on stream " + stream));
                    return false;
                }
            }
            memberRoutes.add(route);
            packets.add(decoded.packet());
        }
        ReceiveRoute unitRoute = bundle ? combineBundle(memberRoutes) : memberRoutes.get(0);
        if (unitRoute == null) {
            fail(ReceiveClose.peerViolation("bundle member route not allowed in a bundle (13.15)"));
            return false;
        }
        ReceiveClose validated = receiver.validateEligible(stream, unitRoute);
        if (validated != null) {
            fail(validated);
            return false;
        }
        receiver.enqueued(stream, entry.openerSeq);
        entry.enqueued = true;
        executing = entry;
        game.onEnqueued(lane, stream, entry.closingSeq);
        game.deliver(lane, entry.closingSeq, packets, bundle);
        return true;
    }

    /**
     * A decoder asked for workspace that is not free. Returns the attempt's own charges, keeps the unit queued
     * and waits for the next receive call. If nothing else holds workspace, no retry can succeed: fail closed.
     */
    private boolean retryLater() {
        releaseCharge(reservedWorkspace);
        if (receiver.slots().workspaceInUse() == 0) {
            fail(ReceiveClose.resourceWait("decoded output does not fit the execution workspace"));
        }
        return false;
    }

    /**
     * Applies an eligible END (13.4): validated and applied as a CONTROL global fence, never decoded or
     * delivered. A violation found on the apply wins; otherwise the connection closes cleanly with 0x0.
     */
    private boolean endFence(int stream, Entry entry) {
        ReceiveClose validated = receiver.validateEligible(stream, END_ROUTE);
        if (validated != null) {
            fail(validated);
            return false;
        }
        receiver.enqueued(stream, entry.openerSeq);
        entry.enqueued = true;
        receiver.applied(stream, entry.openerSeq);
        held[stream] = null;
        ReceiveClose violation = receiver.pendingViolation();
        if (violation != null) {
            fail(violation);
            return false;
        }
        fail(0, NO_REASON);
        return false;
    }

    /** Checks the bundle delimiters: a single record carries none; a bundle is OPEN, MEMBER..., CLOSE. */
    private static ReceiveClose checkDelimiter(Envelope.Bundle marker, int index, int count, boolean bundle) {
        if (!bundle) {
            return marker == Envelope.Bundle.NONE ? null
                    : ReceiveClose.peerViolation("bundle delimiter on a single record");
        }
        Envelope.Bundle expected;
        if (index == 0) {
            expected = Envelope.Bundle.OPEN;
        } else if (index == count - 1) {
            expected = Envelope.Bundle.CLOSE;
        } else {
            expected = Envelope.Bundle.MEMBER;
        }
        if (marker == expected) return null;
        return ReceiveClose.peerViolation("bundle record " + index + " is " + marker + ", expected " + expected
                + " (13.15)");
    }

    /**
     * Builds one bundle's route from its member routes (13.15). Returns null when a member may not join a
     * bundle: an ACK_JOIN or BATCH_JOIN, an epoch change, or a REALTIME member.
     */
    private static ReceiveRoute combineBundle(List<ReceiveRoute> members) {
        boolean global = false;
        boolean view = false;
        boolean interaction = false;
        for (ReceiveRoute member : members) {
            if (member.join() || member.epochChange()) return null;
            if (member.lane() == ReceiveRoute.Lane.REALTIME) return null;
            if (member.lane() == ReceiveRoute.Lane.CHUNK) {
                global = true;
                continue;
            }
            switch (member.fence()) {
                case GLOBAL:
                case FENCE:
                    global = true;
                    break;
                case VIEW:
                    view = true;
                    break;
                case INTERACTION:
                    interaction = true;
                    break;
                case CONTROL:
                case ORDINARY:
                    break;
                default:
                    global = true;
                    break;
            }
        }
        Dependency scope;
        if (global || (view && interaction)) {
            scope = Dependency.GLOBAL;
        } else if (view) {
            scope = Dependency.VIEW;
        } else if (interaction) {
            scope = Dependency.INTERACTION;
        } else {
            scope = Dependency.CONTROL;
        }
        return new ReceiveRoute(ReceiveRoute.Lane.CONTROL, scope, null, false, false);
    }

    /**
     * Builds the route of one decoded record from its schema-2 route (13.6, 13.7). Returns null when a CHUNK
     * position cannot be extracted. An unknown packet id is a global fence on CONTROL (13.5).
     */
    private ReceiveRoute routeOf(LaneGame.Decoded decoded) {
        RouteV2 v2 = routes.routeFor(new RouteKey(protocolNumber, decoded.state(), Direction.CLIENTBOUND,
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
        Extractor.Position position = null;
        if (routeLane == ReceiveRoute.Lane.CHUNK) {
            Extractor extractor = v2.extractor == null ? null : extractors.get(v2.extractor);
            if (extractor == null || decoded.uncompressed() == null) return null;
            try {
                position = extractor.extract(decoded.uncompressed());
            } catch (RuntimeException e) {
                return null;
            }
        }
        boolean join = v2.dependency == Dependency.ACK_JOIN || v2.dependency == Dependency.BATCH_JOIN;
        return new ReceiveRoute(routeLane, v2.dependency, position, v2.epochChange, join);
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

    /** The reservation handed to the game for one decode attempt: charges come from the driver's workspace. */
    private final class Attempt implements LaneGame.Reservation {
        /** Bytes the game reserved during this attempt, across its records. */
        long reserved;

        @Override
        public void reserve(long additionalRetainedBytes, int totalUncompressedBytes) {
            if (additionalRetainedBytes < 0 || !charge(additionalRetainedBytes)) {
                throw new RetryLater();
            }
            reserved += additionalRetainedBytes;
        }
    }

    /** Thrown from {@link Attempt#reserve} when the workspace is full: a retry, not a failure (13.12). */
    private static final class RetryLater extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RetryLater() {
            super("execution workspace is full", null, false, false);
        }
    }

    /** Terminal close: one close, then the driver stops. */
    private void fail(ReceiveClose close) {
        fail(close.errorCode(), closeReason(close));
    }

    private void fail(int errorCode, byte[] reason) {
        if (closed) return;
        terminate();
        sink.close(errorCode, reason);
    }

    /**
     * The one terminal cleanup: cancels the pump (every held slot is released once by the pump), returns every
     * workspace byte the driver holds, and drops the held and waiting units. Sets {@code closed} first, so it
     * runs once.
     */
    private void terminate() {
        closed = true;
        executing = null;
        pump.cancel();
        releaseCharge(reservedWorkspace);
        waiting.clear();
        Arrays.fill(held, null);
    }

    private static byte[] closeReason(ReceiveClose close) {
        if (close.errorCode() == ReceiveClose.RESOURCE) {
            byte[] classified = close.reason();
            return classified != null ? classified : GENERIC_RESOURCE_REASON.clone();
        }
        return NO_REASON;
    }
}
