// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.transport.LaneLoopback;

/**
 * A client and a server over loopback QUIC with a {@link LaneSession} on each end (or a raw
 * stream-level peer in place of one), plus the opaque test packets and recording game they use.
 */
final class LaneRig implements AutoCloseable {
    static final int SPAWN = 1;
    static final int MOVE = 2;
    static final int BLOCK = 3;
    static final int BUNDLE_OPEN = 100;
    static final int BUNDLE_CLOSE = 101;

    /** Ids 1 (control), 2 (realtime), 3 (world) in both directions; everything else is CONTROL. */
    static String tableJson(int moveLane) {
        String moveName = moveLane == 0 ? "control" : "realtime";
        String moveDependency = moveLane == 0 ? "fence" : "lane";
        StringBuilder b = new StringBuilder("{\"schema\":1,\"protocol\":4242,\"default\":\"control\",\"routes\":[");
        String[] directions = {"clientbound", "serverbound"};
        for (int i = 0; i < directions.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"state\":\"play\",\"direction\":\"").append(directions[i])
                    .append("\",\"packet_id\":1,\"lane\":\"control\",\"dependency\":\"fence\"},")
                    .append("{\"state\":\"play\",\"direction\":\"").append(directions[i])
                    .append("\",\"packet_id\":2,\"lane\":\"").append(moveName).append("\",\"dependency\":\"")
                    .append(moveDependency).append("\"},")
                    .append("{\"state\":\"play\",\"direction\":\"").append(directions[i])
                    .append("\",\"packet_id\":3,\"lane\":\"world\",\"dependency\":\"lane\"}");
        }
        return b.append("]}").toString();
    }

    static final RoutingTable TABLE = RoutingTableLoader.parse(tableJson(1));

    /** One Minecraft frame: VarInt(length) then the packet id byte and the payload. */
    static byte[] frame(int packetId, byte[] payload) {
        int inner = 1 + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(inner + 3);
        int v = inner;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
        out.write(packetId);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    static byte[] frame(int packetId, int payloadLength) {
        return frame(packetId, new byte[payloadLength]);
    }

    static byte[] frame(int packetId, String label) {
        return frame(packetId, label.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A decoded test packet. */
    static final class Pkt {
        final int id;
        final String label;

        Pkt(int id, String label) {
            this.id = id;
            this.label = label;
        }

        @Override
        public String toString() {
            return id + ":" + label;
        }
    }

    /** One delivered unit. */
    static final class Delivered {
        final Lane lane;
        final long sequence;
        final List<Object> packets;
        final boolean bundle;

        Delivered(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            this.lane = lane;
            this.sequence = sequence;
            this.packets = packets;
            this.bundle = bundle;
        }

        @Override
        public String toString() {
            return lane + "#" + sequence + packets + (bundle ? " bundle" : "");
        }
    }

    static final class Closed {
        final int code;
        final boolean byPeer;

        Closed(int code, boolean byPeer) {
            this.code = code;
            this.byPeer = byPeer;
        }

        @Override
        public String toString() {
            return "closed " + Integer.toHexString(code) + (byPeer ? " by peer" : " locally");
        }
    }

    /** Records everything; acknowledges deliveries itself unless told to hold them. */
    static final class TestGame implements LaneGame {
        final List<Delivered> deliveries = Collections.synchronizedList(new ArrayList<Delivered>());
        final List<Boolean> writability = Collections.synchronizedList(new ArrayList<Boolean>());
        final CompletableFuture<Closed> closed = new CompletableFuture<>();
        volatile LaneSession session;
        volatile boolean autoApply = true;
        volatile boolean applyOnOtherThread;

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle,
                              Reservation reservation) {
            int prefix = 0;
            while ((frame[prefix] & 0x80) != 0) {
                prefix++;
            }
            prefix++;
            int id = frame[prefix] & 0xff;
            String label = new String(frame, prefix + 1, frame.length - prefix - 1,
                    java.nio.charset.StandardCharsets.UTF_8);
            reservation.reserve(0, frame.length);
            Envelope.Bundle bundle = id == BUNDLE_OPEN ? Envelope.Bundle.OPEN
                    : id == BUNDLE_CLOSE ? Envelope.Bundle.CLOSE
                    : insideBundle ? Envelope.Bundle.MEMBER : Envelope.Bundle.NONE;
            return new Decoded(new Pkt(id, label), ProtocolState.PLAY, id, bundle, false);
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            deliveries.add(new Delivered(lane, sequence, packets, bundle));
            if (autoApply) {
                if (applyOnOtherThread) {
                    new Thread(() -> session.applied(lane, sequence)).start();
                } else {
                    session.applied(lane, sequence);
                }
            }
        }

        void apply(Delivered d) {
            session.applied(d.lane, d.sequence);
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
            writability.add(writable);
        }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
            closed.complete(new Closed(code, byPeer));
        }

        List<String> labels() {
            List<String> result = new ArrayList<String>();
            synchronized (deliveries) {
                for (Delivered d : deliveries) {
                    for (Object p : d.packets) {
                        result.add(((Pkt) p).label);
                    }
                }
            }
            return result;
        }

        int packetCount() {
            int n = 0;
            synchronized (deliveries) {
                for (Delivered d : deliveries) {
                    n += d.packets.size();
                }
            }
            return n;
        }
    }

    /** Collects what a raw stream receives. */
    static final class Bytes extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] chunk = new byte[buf.readableBytes()];
                buf.readBytes(chunk);
                synchronized (bytes) {
                    bytes.write(chunk, 0, chunk.length);
                }
            } finally {
                buf.release();
            }
        }

        byte[] snapshot() {
            synchronized (bytes) {
                return bytes.toByteArray();
            }
        }
    }

    /** What to build. */
    static final class Config {
        LaneLoopback.Options options = new LaneLoopback.Options();
        boolean serverSession = true;
        boolean clientSession = true;
        RoutingTable serverTable = TABLE;
        RoutingTable clientTable = TABLE;
        LaneSession.Timing serverTiming = LaneSession.Timing.DEFAULT;
        LaneSession.Timing clientTiming = LaneSession.Timing.DEFAULT;
        /** The node receive budget of 13.12 both sessions of this rig share. */
        ReceiveBudget nodeBudget = ReceiveBudget.unlimited();
        /** Build the session without a game so a test can call bindGame itself, before or after activate. */
        boolean deferServerGame;
        boolean deferClientGame;
    }

    final LaneLoopback net;
    final TestGame serverGame = new TestGame();
    final TestGame clientGame = new TestGame();
    LaneSession server;
    LaneSession client;
    QuicStreamChannel serverControl;
    QuicStreamChannel clientControl;
    /** Streams a raw (session-less) end was handed, with a byte collector each. */
    final BlockingQueue<QuicStreamChannel> serverRawStreams = new LinkedBlockingQueue<>();
    final BlockingQueue<QuicStreamChannel> clientRawStreams = new LinkedBlockingQueue<>();
    final List<Bytes> serverRawBytes = Collections.synchronizedList(new ArrayList<Bytes>());
    final List<Bytes> clientRawBytes = Collections.synchronizedList(new ArrayList<Bytes>());
    private final CompletableFuture<QuicStreamChannel> serverControlSeen = new CompletableFuture<>();
    private final CompletableFuture<Boolean> preludeRead = new CompletableFuture<>();
    private final List<QuicStreamChannel> serverEarly = new ArrayList<>();
    private final List<QuicStreamChannel> clientEarly = new ArrayList<>();

    private LaneSession serverSessionRef;
    private LaneSession clientSessionRef;

    static LaneRig start() throws Exception {
        return start(new Config());
    }

    static LaneRig start(Config config) throws Exception {
        return new LaneRig(config);
    }

    private LaneRig(Config config) throws Exception {
        config.options.serverSink = stream -> {
            if (stream.streamId() == 0) {
                // The plaintext byte stands for the login that precedes activation.
                stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ((ByteBuf) msg).release();
                        preludeRead.complete(Boolean.TRUE);
                        ctx.pipeline().remove(this);
                    }
                });
                stream.config().setAutoRead(true);
                serverControlSeen.complete(stream);
            } else if (config.serverSession) {
                if (serverSessionRef == null) {
                    serverEarly.add(stream);
                } else {
                    serverSessionRef.acceptStream(stream);
                }
            } else {
                raw(stream, serverRawStreams, serverRawBytes);
            }
        };
        config.options.clientSink = stream -> {
            if (config.clientSession) {
                if (clientSessionRef == null) {
                    clientEarly.add(stream);
                } else {
                    clientSessionRef.acceptStream(stream);
                }
            } else {
                raw(stream, clientRawStreams, clientRawBytes);
            }
        };
        net = LaneLoopback.open(config.options);
        try {
            clientControl = net.clientStream();
            LaneLoopback.write(clientControl, (byte) 'L');
            serverControl = serverControlSeen.get(10, TimeUnit.SECONDS);
            preludeRead.get(10, TimeUnit.SECONDS);
            QuicChannel serverConnection = net.serverConnection.get(10, TimeUnit.SECONDS);
            if (config.serverSession) {
                serverConnection.eventLoop().submit(() -> {
                    server = new LaneSession(serverConnection, LaneSession.Role.SERVER, serverControl,
                            config.serverTable, config.deferServerGame ? null : serverGame,
                            config.nodeBudget, config.serverTiming);
                    serverGame.session = server;
                    serverSessionRef = server;
                    for (QuicStreamChannel s : serverEarly) {
                        server.acceptStream(s);
                    }
                }).get(10, TimeUnit.SECONDS);
            }
            if (config.clientSession) {
                net.clientConnection.eventLoop().submit(() -> {
                    client = new LaneSession(net.clientConnection, LaneSession.Role.CLIENT, clientControl,
                            config.clientTable, config.deferClientGame ? null : clientGame,
                            config.nodeBudget, config.clientTiming);
                    clientGame.session = client;
                    clientSessionRef = client;
                    for (QuicStreamChannel s : clientEarly) {
                        client.acceptStream(s);
                    }
                }).get(10, TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            net.close();
            throw t;
        }
    }

    private static void raw(QuicStreamChannel stream, BlockingQueue<QuicStreamChannel> streams, List<Bytes> bytes) {
        Bytes collector = new Bytes();
        stream.pipeline().addLast(collector);
        bytes.add(collector);
        stream.config().setAutoRead(true);
        streams.add(stream);
    }

    static boolean await(BooleanSupplier condition, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    /**
     * Both sessions' activation, server first and complete before the client starts: as in the
     * protocol, the client's stream 4 must never reach a server that has not activated yet.
     */
    void activateBoth() throws Exception {
        activate(server, null);
        client.activate(null);
    }

    void activate(LaneSession session, java.nio.ByteBuffer leftover) throws Exception {
        session.activate(leftover);
        session.eventLoop().submit(() -> null).get(10, TimeUnit.SECONDS);
    }

    static long[] snapshot(LaneSession session) throws Exception {
        return session.eventLoop().submit(session::snapshot).get(10, TimeUnit.SECONDS);
    }

    /** The sender's last-assigned sequences per lane, from the session's event loop. */
    static long[] assigned(LaneSession session) throws Exception {
        return session.eventLoop().submit(session::assignedSequences).get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        net.close();
    }
}
