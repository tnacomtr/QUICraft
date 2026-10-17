// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;
import java.util.ArrayList;
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
 * <p>Thread confinement: this class is not thread-safe. Every call (receive, acknowledged, stall and
 * withholding notices) must run on the one receive thread. The game's acknowledgements from other threads
 * are marshalled onto that thread by the integration (U8f).
 */
public final class SessionReceiveDriver {
    /** Missing-close limit for a bundle opener, 13.15. */
    public static final long BUNDLE_CLOSE_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** The bounded generic 0x12 phrase used when no classified reason is present (orchestrator decision). */
    static final byte[] GENERIC_RESOURCE_REASON = "resource".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] NO_REASON = new byte[0];

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

    /** Returns true once the driver has closed the connection. */
    public boolean closed() {
        return closed;
    }

    /** Returns the receiver, for the integration and for tests. */
    public StreamReceiver receiver() {
        return receiver;
    }

    /**
     * Reads at most one step from the pump, admits what completed and delivers every eligible head.
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
        if (entry == null || !entry.enqueued || entry.closingSeq != closingSequence) {
            throw new IllegalStateException("acknowledgement for an unexpected unit on stream " + stream);
        }
        receiver.applied(stream, entry.openerSeq);
        held[stream] = null;
        receiver.releaseWorkspace(entry.wireBytes);
        ReceiveClose violation = receiver.pendingViolation();
        if (violation != null) {
            fail(violation);
            return;
        }
        pump.commit(entry.unit);
        game.onApplicationComplete(laneOf(stream), stream, closingSequence);
        drive();
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

    /** Admits waiting units, then enqueues every eligible head until none is left or the driver closes. */
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

    /** Decodes, validates and enqueues the eligible head of {@code stream}. Returns false when closed. */
    private boolean enqueue(int stream) {
        Entry entry = held[stream];
        Lane lane = laneOf(stream);
        if (!receiver.reserveWorkspace(entry.wireBytes)) {
            fail(ReceiveClose.resourceWait("execution workspace"));
            return false;
        }
        List<Object> packets = new ArrayList<Object>(entry.unit.recordCount());
        List<ReceiveRoute> memberRoutes = new ArrayList<ReceiveRoute>(entry.unit.recordCount());
        boolean bundle = entry.unit.recordCount() > 1;
        List<rs.sudoe.quicraft.core.multistream.Record> records = entry.unit.records();
        for (int i = 0; i < records.size(); i++) {
            rs.sudoe.quicraft.core.multistream.Record record = records.get(i);
            boolean inside = i > 0;
            LaneGame.Decoded decoded;
            try {
                decoded = game.decode(lane, record.sequence(), record.payload(), inside, NO_RESERVATION);
            } catch (Exception e) {
                receiver.releaseWorkspace(entry.wireBytes);
                fail(ReceiveClose.peerViolation("decode failed: " + e));
                return false;
            }
            if (decoded == null) {
                receiver.releaseWorkspace(entry.wireBytes);
                fail(ReceiveClose.peerViolation("decoder returned nothing"));
                return false;
            }
            ReceiveClose markerClose = checkDelimiter(decoded.bundle(), i, records.size(), bundle);
            if (markerClose == null && bundle && decoded.stateChange()) {
                markerClose = ReceiveClose.peerViolation("state change inside a bundle (13.15)");
            }
            if (markerClose != null) {
                receiver.releaseWorkspace(entry.wireBytes);
                fail(markerClose);
                return false;
            }
            if (decoded.uncompressed() != null) {
                game.onDecodedUncompressed(lane, stream, record.sequence(), decoded.uncompressed());
            }
            ReceiveRoute route = routeOf(decoded);
            if (route == null) {
                receiver.releaseWorkspace(entry.wireBytes);
                fail(ReceiveClose.peerViolation("no usable route for packet " + decoded.packetId()));
                return false;
            }
            if (bundle) {
                // Members are not the opener: validateEligible checks only the opener's body, so each member is checked here.
                if (!validator.valid(stream, record, route)) {
                    receiver.releaseWorkspace(entry.wireBytes);
                    fail(ReceiveClose.peerViolation("member validation failed on stream " + stream));
                    return false;
                }
            }
            memberRoutes.add(route);
            packets.add(decoded.packet());
        }
        ReceiveRoute unitRoute = bundle ? combineBundle(memberRoutes) : memberRoutes.get(0);
        if (unitRoute == null) {
            receiver.releaseWorkspace(entry.wireBytes);
            fail(ReceiveClose.peerViolation("bundle member route not allowed in a bundle (13.15)"));
            return false;
        }
        ReceiveClose validated = receiver.validateEligible(stream, unitRoute);
        if (validated != null) {
            receiver.releaseWorkspace(entry.wireBytes);
            fail(validated);
            return false;
        }
        receiver.enqueued(stream, entry.openerSeq);
        entry.enqueued = true;
        game.onEnqueued(lane, stream, entry.closingSeq);
        game.deliver(lane, entry.closingSeq, packets, bundle);
        return true;
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

    /** Terminal close: one close, then the driver stops. */
    private void fail(ReceiveClose close) {
        fail(close.errorCode(), closeReason(close));
    }

    private void fail(int errorCode, byte[] reason) {
        if (closed) return;
        closed = true;
        sink.close(errorCode, reason);
    }

    private static byte[] closeReason(ReceiveClose close) {
        if (close.errorCode() == ReceiveClose.RESOURCE) {
            byte[] classified = close.reason();
            return classified != null ? classified : GENERIC_RESOURCE_REASON.clone();
        }
        return NO_REASON;
    }

    /** The reservation handed to the game: this driver's workspace already covers the unit, so nothing more. */
    private static final LaneGame.Reservation NO_RESERVATION = (extra, total) -> { };
}
