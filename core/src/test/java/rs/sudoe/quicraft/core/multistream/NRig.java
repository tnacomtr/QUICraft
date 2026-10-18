// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.resource.NodePools;
import rs.sudoe.quicraft.core.routing.Lane;
import rs.sudoe.quicraft.core.routing.ProtocolState;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.routing.RoutingTableLoader;
import rs.sudoe.quicraft.core.transport.LaneLoopback;

/**
 * A client and a server over loopback QUIC with an {@link NStreamSession} on each end (or a raw stream-level peer
 * in place of one), with a schema-2 test table and a recording game.
 */
final class NRig implements AutoCloseable {
    static final int CONTROL_ID = 1;
    static final int REALTIME_ID = 2;
    static final int CHUNK_ID = 3;
    static final int VIEW_ID = 4;
    static final int INTERACTION_ID = 5;
    static final int JOIN_ID = 6;
    static final int BUNDLE_OPEN = 100;
    static final int BUNDLE_CLOSE = 101;

    private static final String LIMIT = "{\"maxServerboundRecord\":32768,\"maxServerboundRealtimeRecord\":510,"
            + "\"maxDecodedFootprint\":1048576,\"ordinaryReadAllowance\":5619,"
            + "\"decodeFootprintBound\":\"primitive.raw_copies.v1\"}";

    private static String route(String direction, int id, String lane, String dependency, String position) {
        boolean small = direction.equals("serverbound") && lane.equals("control");
        return "{\"state\":\"play\",\"direction\":\"" + direction + "\",\"packet_id\":" + id + ",\"lane\":\"" + lane
                + "\",\"dependency\":\"" + dependency + "\",\"position\":" + position + ",\"epoch_change\":false"
                + (small ? ",\"ordinarySmall\":false" : "") + "}";
    }

    /**
     * Control (1), realtime (2) and view (4) in both directions; chunk with an xz extractor (3) and an ACK_JOIN
     * chunk (6) clientbound; interaction (5) serverbound. Everything else is CONTROL/global.
     */
    static String tableJson() {
        StringBuilder limits = new StringBuilder("{");
        String[] states = {"handshaking", "status", "login", "configuration", "play"};
        for (int i = 0; i < states.length; i++) {
            limits.append(i > 0 ? "," : "").append('"').append(states[i]).append("\":").append(LIMIT);
        }
        limits.append('}');
        List<String> routes = new ArrayList<String>();
        for (String d : new String[] {"clientbound", "serverbound"}) {
            routes.add(route(d, CONTROL_ID, "control", "control", "null"));
            routes.add(route(d, REALTIME_ID, "realtime", "lane", "null"));
            routes.add(route(d, VIEW_ID, "control", "view", "null"));
        }
        routes.add(route("clientbound", CHUNK_ID, "chunk", "lane", "\"xz\""));
        routes.add(route("clientbound", JOIN_ID, "chunk", "ack_join", "\"join\""));
        routes.add(route("serverbound", INTERACTION_ID, "control", "interaction", "null"));
        return "{\"schema\":2,\"protocol\":4243,\"default\":\"global\",\"extractors\":{"
                + "\"xz\":\"primitive.chunk_xz.v1\",\"join\":\"primitive.join.v1\"},\"serverboundLimits\":" + limits
                + ",\"routes\":[" + String.join(",", routes) + "]}";
    }

    static final RoutingTable TABLE = RoutingTableLoader.parse(tableJson());

    static final long OUTBOUND_BYTES = 4L << 20;

    /** Small test pools: a 4 MiB outbound node, IP, account and connection cap. */
    static SessionResources resources() {
        NodePools node = new NodePools(64L << 20, 64L << 20, OUTBOUND_BYTES, 10, false, NodePools.MIN_EXCESS);
        return new SessionResources(node, OUTBOUND_BYTES, OUTBOUND_BYTES, OUTBOUND_BYTES, 16L << 20, 16L << 20,
                SessionResources.defaultProfile(), System::nanoTime);
    }

    static NStreamSession.Timing timing(long tickMillis, long openTimeoutMillis) {
        return new NStreamSession.Timing(System::nanoTime, TimeUnit.MILLISECONDS.toNanos(openTimeoutMillis),
                TimeUnit.SECONDS.toNanos(10), TimeUnit.MILLISECONDS.toNanos(tickMillis));
    }

    /** A play frame with a label body. */
    static byte[] frame(int id, String label) {
        return SessionRigSupport.frame(id, label);
    }

    /** A chunk frame: big-endian x and z, then the label. */
    static byte[] chunkFrame(int x, int z, String label) {
        byte[] text = label.getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.allocate(8 + text.length);
        body.putInt(x).putInt(z).put(text);
        return SessionRigSupport.frame(CHUNK_ID, body.array());
    }

    /** The uncompressed packet id and position, as a game hands it to the session for extraction. */
    static byte[] chunkUncompressed(int x, int z) {
        return ByteBuffer.allocate(9).put((byte) CHUNK_ID).putInt(x).putInt(z).array();
    }

    /** A decoded test packet. */
    static final class Pkt {
        final int id;
        final String label;
        final int x;
        final int z;

        Pkt(int id, String label, int x, int z) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.z = z;
        }

        @Override
        public String toString() {
            return id + ":" + label;
        }
    }

    /** One delivered unit, with the logical stream it executed from. */
    static final class Got {
        final Lane lane;
        final int stream;
        final long sequence;
        final List<Object> packets;

        Got(Lane lane, int stream, long sequence, List<Object> packets) {
            this.lane = lane;
            this.stream = stream;
            this.sequence = sequence;
            this.packets = packets;
        }

        @Override
        public String toString() {
            return lane + "/" + stream + "#" + sequence + packets;
        }
    }

    /** Records every delivery and acknowledges it. */
    static final class Game implements LaneGame {
        final List<Got> got = Collections.synchronizedList(new ArrayList<Got>());
        final CompletableFuture<SessionRigSupport.Closed> closed = new CompletableFuture<>();
        volatile NStreamSession session;
        volatile long decodeReserveBytes;
        volatile boolean autoApply = true;
        private volatile int lastStream = -1;

        @Override
        public void onEnqueued(Lane lane, int logicalStream, long sequence) {
            lastStream = logicalStream;
        }

        @Override
        public Decoded decode(Lane lane, long sequence, byte[] frame, boolean insideBundle, Reservation reservation) {
            int prefix = 0;
            while ((frame[prefix] & 0x80) != 0) {
                prefix++;
            }
            prefix++;
            byte[] content = new byte[frame.length - prefix];
            System.arraycopy(frame, prefix, content, 0, content.length);
            return decodeContent(content, insideBundle, reservation);
        }

        @Override
        public Decoded decodeInflated(Lane lane, long sequence, byte[] content, boolean insideBundle,
                Reservation reservation) {
            return decodeContent(content, insideBundle, reservation);
        }

        private Decoded decodeContent(byte[] content, boolean insideBundle, Reservation reservation) {
            reservation.reserve(decodeReserveBytes, content.length);
            int id = content[0] & 0xff;
            Pkt pkt;
            byte[] uncompressed;
            if (id == CHUNK_ID) {
                ByteBuffer body = ByteBuffer.wrap(content, 1, content.length - 1);
                int x = body.getInt();
                int z = body.getInt();
                pkt = new Pkt(id, new String(content, 9, content.length - 9, StandardCharsets.UTF_8), x, z);
                uncompressed = chunkUncompressed(x, z);
            } else {
                pkt = new Pkt(id, new String(content, 1, content.length - 1, StandardCharsets.UTF_8), 0, 0);
                uncompressed = content;
            }
            Envelope.Bundle bundle = id == BUNDLE_OPEN ? Envelope.Bundle.OPEN
                    : id == BUNDLE_CLOSE ? Envelope.Bundle.CLOSE
                    : insideBundle ? Envelope.Bundle.MEMBER : Envelope.Bundle.NONE;
            return new Decoded(pkt, ProtocolState.PLAY, id, bundle, false, 0, content.length, uncompressed);
        }

        @Override
        public void deliver(Lane lane, long sequence, List<Object> packets, boolean bundle) {
            got.add(new Got(lane, lastStream, sequence, packets));
            if (autoApply) session.applied(lane, sequence);
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
        }

        @Override
        public void onClosed(int code, boolean byPeer, Throwable cause) {
            closed.complete(new SessionRigSupport.Closed(code, byPeer, cause));
        }

        List<Pkt> packets() {
            List<Pkt> out = new ArrayList<Pkt>();
            synchronized (got) {
                for (Got g : got) {
                    for (Object p : g.packets) {
                        out.add((Pkt) p);
                    }
                }
            }
            return out;
        }

        int count(int id) {
            int n = 0;
            for (Pkt p : packets()) {
                if (p.id == id) {
                    n++;
                }
            }
            return n;
        }

        List<Got> units() {
            synchronized (got) {
                return new ArrayList<Got>(got);
            }
        }
    }

    /** What to build. */
    static final class Config {
        int shards = 2;
        boolean serverSession = true;
        boolean clientSession = true;
        boolean compressed;
        NStreamSession.Timing serverTiming = timing(50, 10_000);
        NStreamSession.Timing clientTiming = timing(50, 10_000);
        SessionResources serverResources = resources();
        SessionResources clientResources = resources();
        RoutingTable table = TABLE;
        /** The 13.12 account key of the server session's charges, or null (see NStreamSession). */
        String serverAccount;
    }

    final Config config;
    final LaneLoopback net;
    final Game serverGame = new Game();
    final Game clientGame = new Game();
    final SessionRigSupport.EarlyControl earlyControl = new SessionRigSupport.EarlyControl();
    NStreamSession server;
    NStreamSession client;
    QuicChannel serverConnection;
    QuicStreamChannel serverControl;
    QuicStreamChannel clientControl;
    final BlockingQueue<QuicStreamChannel> serverRawStreams = new LinkedBlockingQueue<>();
    final BlockingQueue<QuicStreamChannel> clientRawStreams = new LinkedBlockingQueue<>();
    final List<SessionRigSupport.Bytes> clientRawBytes = Collections.synchronizedList(new ArrayList<SessionRigSupport.Bytes>());
    private final CompletableFuture<QuicStreamChannel> serverControlSeen = new CompletableFuture<>();
    private final CompletableFuture<Boolean> preludeRead = new CompletableFuture<>();
    private final List<QuicStreamChannel> serverEarly = new ArrayList<>();
    private final List<QuicStreamChannel> clientEarly = new ArrayList<>();
    private volatile NStreamSession serverRef;
    private volatile NStreamSession clientRef;

    static NRig start(Config config) throws Exception {
        return new NRig(config);
    }

    static NRig start(int shards) throws Exception {
        Config config = new Config();
        config.shards = shards;
        return new NRig(config);
    }

    private NRig(Config config) throws Exception {
        this.config = config;
        LaneLoopback.Options options = new LaneLoopback.Options();
        options.serverSink = stream -> {
            if (stream.streamId() == 0) {
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
                if (serverRef == null) {
                    serverEarly.add(stream);
                } else {
                    serverRef.acceptStream(stream);
                }
            } else {
                raw(stream, serverRawStreams, new ArrayList<SessionRigSupport.Bytes>());
            }
        };
        options.clientSink = stream -> {
            if (config.clientSession) {
                if (clientRef == null) {
                    clientEarly.add(stream);
                } else {
                    clientRef.acceptStream(stream);
                }
            } else {
                raw(stream, clientRawStreams, clientRawBytes);
            }
        };
        net = LaneLoopback.open(options);
        try {
            clientControl = net.clientStream(earlyControl);
            LaneLoopback.write(clientControl, (byte) 'L');
            serverControl = serverControlSeen.get(10, TimeUnit.SECONDS);
            preludeRead.get(10, TimeUnit.SECONDS);
            serverConnection = net.serverConnection.get(10, TimeUnit.SECONDS);
            SessionContext context = SessionContext.of(config.shards);
            if (config.serverSession) {
                serverConnection.eventLoop().submit(() -> {
                    server = new NStreamSession(serverConnection, NStreamSession.Role.SERVER, serverControl, config.table,
                            serverGame, context, config.serverResources, config.serverTiming, config.compressed,
                            config.serverAccount);
                    serverGame.session = server;
                    serverRef = server;
                    for (QuicStreamChannel s : serverEarly) {
                        server.acceptStream(s);
                    }
                }).get(10, TimeUnit.SECONDS);
            }
            if (config.clientSession) {
                net.clientConnection.eventLoop().submit(() -> {
                    client = new NStreamSession(net.clientConnection, NStreamSession.Role.CLIENT, clientControl,
                            config.table,
                            clientGame, context, config.clientResources, config.clientTiming, config.compressed);
                    clientGame.session = client;
                    clientRef = client;
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

    private static void raw(QuicStreamChannel stream, BlockingQueue<QuicStreamChannel> streams,
            List<SessionRigSupport.Bytes> bytes) {
        SessionRigSupport.Bytes collector = new SessionRigSupport.Bytes();
        stream.pipeline().addLast(collector);
        bytes.add(collector);
        stream.config().setAutoRead(true);
        streams.add(stream);
    }

    /** Activates the server, then the client with what its stream-0 owner read, and waits for every stream. */
    void activateBoth() throws Exception {
        activateServer();
        activateClient();
        awaitStreams();
    }

    void activateServer() throws Exception {
        onLoop(server, () -> {
            server.activate(null);
            return null;
        });
    }

    void activateClient() throws Exception {
        onLoop(client, () -> {
            client.activate(earlyControl.take());
            return null;
        });
    }

    /** Waits until each session has all N+2 streams attached. */
    void awaitStreams() throws Exception {
        int streams = config.shards + 2;
        for (NStreamSession session : new NStreamSession[] {server, client}) {
            if (session == null) {
                continue;
            }
            boolean ready = SessionRigSupport.await(() -> {
                try {
                    return onLoop(session, () -> {
                        for (int i = 0; i < streams; i++) {
                            if (session.channelOf(i) == null) {
                                return false;
                            }
                        }
                        return true;
                    });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, 10_000);
            if (!ready) {
                throw new IllegalStateException("streams did not open");
            }
        }
    }

    /** Waits until every session has read all of its expected peer preambles (13.3). */
    void awaitPeerPreambles() throws Exception {
        for (NStreamSession session : new NStreamSession[] {server, client}) {
            if (session == null) {
                continue;
            }
            boolean settled = SessionRigSupport.await(() -> {
                try {
                    return onLoop(session, () -> session.peerPreamblesSeen());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, 10_000);
            if (!settled) {
                throw new IllegalStateException("peer preambles did not settle");
            }
        }
    }

    static <T> T onLoop(NStreamSession session, Callable<T> task) throws Exception {
        return session.eventLoop().submit(task).get(10, TimeUnit.SECONDS);
    }

    static QuicStreamChannel channel(NStreamSession session, int index) throws Exception {
        return onLoop(session, () -> session.channelOf(index));
    }

    @Override
    public void close() {
        net.close();
    }
}
