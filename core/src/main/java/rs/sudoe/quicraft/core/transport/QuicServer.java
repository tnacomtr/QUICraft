// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicCodecDispatcher;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicConnectionIdGenerator;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.ReferenceCountUtil;
import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.MultistreamProtocolException;
import rs.sudoe.quicraft.core.multistream.ReceiveBudget;
import rs.sudoe.quicraft.core.multistream.SessionResources;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * QUIC listener. Each client's v1 stream is handed to the {@link StreamAcceptor} once the QUIC
 * handshake has completed, or, for 0-RTT data whose preamble carries an unused early token, at
 * once; such a connection must then complete its handshake within {@link #confirmMillis} or is
 * closed. A replayed first flight carries a used token, waits for a handshake that never
 * completes, and never reaches the game (docs/protocol.md §8).
 *
 * <p>Each bind limits connection attempts and open connections, per IP and overall, and validates
 * client addresses with Retry under load or for an attempt over its limit (docs/protocol.md §9):
 * an {@link IngressGuard} drops floods of Initial packets before the codec, an
 * {@link AdmissionTokenHandler} answers attempts over a limit, including the open-connection
 * limits, with at most a Retry, and each connection holds an open-connection lease until it closes.
 *
 * <p>A listener may also run on several UDP sockets bound to the same address with SO_REUSEPORT,
 * each socket on its own event loop, so one node's QUIC work spreads over several threads
 * (docs/protocol.md §11). Only the sockets and their loops are multiplied: admission, the Retry
 * key, the early tokens, the stream acceptor and everything else below stay node-wide, one set per
 * listener. Netty's {@link QuicCodecDispatcher} routes a datagram whose destination connection id
 * names another socket's codec to that socket, so a packet the kernel's 4-tuple hash put on the
 * wrong socket still reaches the connection's codec. That hand-over is bounded per target socket
 * ({@link ForwardLimiter}): over the bound the datagram is released and counted, so one socket's
 * loop can never queue unbounded work on another. A short-header datagram that stops before the
 * whole connection id is dropped and counted there too, because the dispatcher's own parse of it
 * throws; anything else a datagram handler throws is swallowed by a last handler on each socket's
 * pipeline ({@link DatagramExceptionBackstop}), which would otherwise reach Netty's tail.
 */
public final class QuicServer implements Closeable {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    /** RFC 9000 §20.1: transport error CONNECTION_REFUSED. */
    private static final int CONNECTION_REFUSED = 0x2;
    /** Marks a connection refused by the open-connection limits; its streams are ignored. */
    private static final io.netty.util.AttributeKey<Boolean> REFUSED =
            io.netty.util.AttributeKey.valueOf(QuicServer.class, "refused");
    /** The /3 connection's staged pre-login lifecycle (12.13), see {@link #preLoginStage}. */
    private static final io.netty.util.AttributeKey<PreLoginConnection> PRE_LOGIN =
            io.netty.util.AttributeKey.valueOf(QuicServer.class, "preLogin");
    /**
     * Chunk streams charged for a staged connection. N is not negotiated before OFFER, so every
     * staged connection is charged for the largest N (13.2): conservative, and at most six streams.
     */
    static final int STAGED_LANES = 6;
    /** How often a staged connection's pre-login deadline is checked (12.13). */
    private static final long STAGED_CHECK_MILLIS = 1_000;
    /** RFC 9001 §4.8: CRYPTO_ERROR 0x100 + TLS alert handshake_failure (40). */
    private static final int CRYPTO_ERROR_HANDSHAKE_FAILURE = 0x100 + 40;
    /** Most UDP sockets one listener binds; the system property below is capped at this. */
    public static final int MAX_UDP_SOCKETS = 64;
    /** {@code 1..64}: the number of UDP sockets a listener binds; see {@link #defaultSocketCount()}. */
    private static final String UDP_SOCKETS_PROPERTY = "quicraft.udpSockets";
    private static final java.util.concurrent.atomic.AtomicBoolean UDP_SOCKETS_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean SOCKET_COUNT_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Receives each accepted stream on the stream's thread. Must set a listener, then enable auto-read. */
    public interface StreamAcceptor {
        void accept(QuicByteStream stream);
    }

    private final EventLoopGroup group;
    /** One UDP socket per element, in bind order; the first one holds the address. */
    private final List<Channel> channels;
    /** Everything node-wide, one set per listener, shared by every socket's codec. */
    private final Shared shared;

    private QuicServer(EventLoopGroup group, List<Channel> channels, Shared shared) {
        this.group = group;
        this.channels = channels;
        this.shared = shared;
    }

    /** The node's receive budget this listener enforces (13.12); unlimited when none was configured. */
    public ReceiveBudget nodeBudget() {
        return shared.nodeBudget;
    }

    /** Connections holding an open-connection lease whose handshake hasn't completed, for tests. */
    int pendingHandshakes() {
        return shared.admission.pendingLeases();
    }

    /** Tests only: the transport configuration this listener actually applies. */
    TransportConfig effectiveConfigForTests() {
        return shared.config;
    }

    /** Retry packets sent so far, for diagnostics and tests. */
    long retriesSent() {
        return shared.retries.get();
    }

    /** UDP sockets this listener bound, in bind order. */
    int socketCount() {
        return channels.size();
    }

    /**
     * Connections admitted on each UDP socket, indexed in bind order; empty when the listener has a
     * single socket, which keeps no per-socket counts. Each element is only ever written by its own
     * socket's event loop, for tests. The index comes from the connection id generator the base
     * class built for the socket, so a connection is always counted on the socket that accepted it,
     * even when sockets are added concurrently.
     */
    int[] acceptedConnectionsPerSocket() {
        AtomicIntegerArray accepted = shared.accepted;
        if (accepted == null) {
            return new int[0];
        }
        int[] copy = new int[accepted.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = accepted.get(i);
        }
        return copy;
    }

    /**
     * Datagrams the forward bound dropped on each UDP socket, indexed by the socket each dropped
     * datagram named ({@link ForwardLimiter}); empty when the listener has a single socket, which
     * has no dispatcher and no forwards. Diagnostics for tests, like
     * {@link #acceptedConnectionsPerSocket()}.
     */
    int[] droppedForwardsPerSocket() {
        AtomicIntegerArray dropped = shared.droppedForwards;
        if (dropped == null) {
            return new int[0];
        }
        int[] copy = new int[dropped.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = dropped.get(i);
        }
        return copy;
    }

    /**
     * Datagrams each UDP socket dropped because they were a short header that stopped before the
     * whole connection id, indexed in bind order; empty when the listener has a single socket, which
     * has no dispatcher and drops none. Each such datagram is counted on the socket that read it
     * ({@link ForwardLimiter}). Diagnostics for tests, like {@link #acceptedConnectionsPerSocket()}.
     */
    int[] droppedMalformedPerSocket() {
        AtomicIntegerArray dropped = shared.droppedMalformed;
        if (dropped == null) {
            return new int[0];
        }
        int[] copy = new int[dropped.length()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = dropped.get(i);
        }
        return copy;
    }

    /**
     * Closes this listener's connections that were passed to the game on 0-RTT data, haven't
     * completed their handshake, and came from {@code login}'s IP with a Login Start for
     * {@code name} (case-insensitive), except a connection whose own address is {@code login}.
     * The platform calls this for each login: for a TCP login, such a connection is the client's
     * abandoned QUIC attempt, whose login would otherwise hold the name (docs/protocol.md §8).
     * Completes, with how many were closed, once they are.
     */
    public CompletableFuture<Integer> closeUnconfirmedEarly(InetSocketAddress login, String name) {
        return shared.unconfirmed.close(login, name);
    }

    /** Streams passed to the game on 0-RTT data so far, for diagnostics and tests. */
    public long earlyReleases() {
        return shared.unconfirmed.released.get();
    }

    /** Connections the client closed with {@code 0x4} (early data abandoned) so far, for diagnostics and tests. */
    public long earlyAbandoned() {
        return shared.unconfirmed.abandoned.get();
    }

    /** Binds {@code address} and runs QUIC on core's own thread, one UDP socket. */
    public static QuicServer bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor) throws Exception {
        return bind(address, identity, config, acceptor, null);
    }

    /**
     * As {@link #bind(InetSocketAddress, ServerIdentity, TransportConfig, StreamAcceptor)}, for a
     * {@code /3} listener (13.1): the QUIC ALPN is {@link Protocol#ALPN_V3} and the 13.13
     * transport parameters are in force. {@code v3} null is exactly the {@code /2} listener above;
     * there is no dual listener, so a {@code /2}-only client fails the ALPN and falls back (13.1).
     *
     * <p>On a native that cannot cap quiche's flow-control windows this logs one INFO line and
     * binds the plain {@code /2} listener instead (12.3, 13.13).
     */
    public static QuicServer bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor, V3Listener v3) throws Exception {
        return bind(address, 1, identity, config, acceptor, v3);
    }

    /**
     * As {@link #bind(InetSocketAddress, ServerIdentity, TransportConfig, StreamAcceptor)}, but on
     * {@code socketCount} UDP sockets bound to the same address with SO_REUSEPORT, each on its own
     * event loop thread of an {@code socketCount}-thread group, so one node's QUIC work spreads
     * over several threads (docs/protocol.md §11).
     *
     * <p>SO_REUSEPORT has to be looked up reflectively (core is Java 8 bytecode,
     * {@code StandardSocketOptions.SO_REUSEPORT} exists only on JDK 9+); where it does not exist or
     * the JDK reports it unsupported, the listener falls back to one socket. A count of 1 or less
     * is exactly {@link #bind(InetSocketAddress, ServerIdentity, TransportConfig, StreamAcceptor)}
     * and adds no dispatcher, so this can never change a single-socket listener's behaviour.
     *
     * <p>The first socket binds {@code address}; when the request had port 0, the others bind the
     * port the first one got. If binding any later socket fails, the ones already bound and the
     * group are closed before the failure is rethrown.
     * Outside Linux the count is always 1.
     */
    public static QuicServer bind(InetSocketAddress address, int socketCount, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor) throws Exception {
        return bind(address, socketCount, identity, config, acceptor, null);
    }

    /** As the {@code socketCount} bind above, for a {@code /3} listener (13.1) or a {@code /2} one. */
    public static QuicServer bind(InetSocketAddress address, int socketCount, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor, V3Listener v3Requested) throws Exception {
        V3Listener v3 = enableV3(v3Requested);
        if (v3 != null) {
            config = config.withMultistream();
        }
        int count = socketCount;
        if (count > MAX_UDP_SOCKETS) {
            if (SOCKET_COUNT_WARNING.compareAndSet(false, true)) {
                LOG.warning("A QUIC listener binds at most " + MAX_UDP_SOCKETS + " UDP sockets; using "
                        + MAX_UDP_SOCKETS + " of the requested " + socketCount);
            }
            count = MAX_UDP_SOCKETS;
        }
        if (count > 1 && !System.getProperty("os.name", "").startsWith("Linux")) {
            // Only Linux balances unicast UDP over SO_REUSEPORT sockets; elsewhere one socket
            // would receive everything while the others sit idle (docs/protocol.md §11).
            count = 1;
        }
        if (count <= 1) {
            Shared shared = new Shared(1, identity, config, acceptor, v3);
            EventLoopGroup group = Codecs.newGroup("quicraft-server", 1);
            try {
                Channel channel = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) throws Exception {
                                ch.pipeline().addLast(IngressGuard.forConfig(shared.ingress),
                                        codec(shared, Codecs.LOCAL_CONNECTION_ID_LENGTH, null, 0));
                            }
                        })
                        .bind(address).sync().channel();
                List<Channel> single = new ArrayList<>(1);
                single.add(channel);
                return new QuicServer(group, single, shared);
            } catch (Exception | Error e) {
                group.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
                throw e;
            }
        }
        ChannelOption<Boolean> reusePort = ReusePort.OPTION;
        if (reusePort == null || !ReusePort.SUPPORTED) {
            LOG.info("SO_REUSEPORT is unavailable here; binding one UDP socket instead of " + count);
            return bind(address, identity, config, acceptor, v3);
        }
        Shared shared = new Shared(count, identity, config, acceptor, v3);
        SocketDispatcher dispatcher = new SocketDispatcher(shared, count);
        EventLoopGroup group = Codecs.newGroup("quicraft-server", count);
        List<Channel> channels = new ArrayList<>(count);
        try {
            InetSocketAddress bound = address;
            for (int i = 0; i < count; i++) {
                Channel channel = new Bootstrap().group(group.next()).channel(NioDatagramChannel.class)
                        .option(reusePort, Boolean.TRUE)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) throws Exception {
                                ch.pipeline().addLast(IngressGuard.forConfig(shared.ingress), dispatcher);
                            }
                        })
                        .bind(bound).sync().channel();
                channels.add(channel);
                if (i == 0) {
                    // The port the kernel chose for the first socket, if any, binds the rest.
                    bound = (InetSocketAddress) channel.localAddress();
                }
            }
            return new QuicServer(group, channels, shared);
        } catch (Exception | Error e) {
            closeQuietly(channels, true);
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
            throw e;
        }
    }

    /**
     * Runs QUIC on the platform's {@code loop} over its bound {@code socket} (hosted transport,
     * docs/protocol.md §11): accepted streams can go straight into a pipeline on the same loop,
     * with no thread hop. {@link #close()} closes the socket.
     */
    public static QuicServer bind(HostLoop loop, HostDatagramSocket socket, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor) throws Exception {
        return bind(java.util.Collections.singletonList(loop), java.util.Collections.singletonList(socket),
                identity, config, acceptor, null);
    }

    /** Hosted, for a {@code /3} listener (13.1) or a {@code /2} one; see the two binds above. */
    public static QuicServer bind(HostLoop loop, HostDatagramSocket socket, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor, V3Listener v3) throws Exception {
        return bind(java.util.Collections.singletonList(loop), java.util.Collections.singletonList(socket),
                identity, config, acceptor, v3);
    }

    /**
     * As {@link #bind(HostLoop, HostDatagramSocket, ServerIdentity, TransportConfig, StreamAcceptor)},
     * but over several host sockets (hosted transport, docs/protocol.md §11), one per host loop:
     * the platform binds every socket to the same address with SO_REUSEPORT and hands each its own
     * loop, so QUIC work spreads over those threads while every limit stays node-wide. Both lists
     * have the same size, and socket {@code i} runs on loop {@code i}; a single pair is exactly the
     * single-socket bind above, with no dispatcher.
     */
    public static QuicServer bind(List<HostLoop> loops, List<HostDatagramSocket> sockets, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor) throws Exception {
        return bind(loops, sockets, identity, config, acceptor, null);
    }

    /** As the hosted bind above, for a {@code /3} listener (13.1) or a {@code /2} one. */
    public static QuicServer bind(List<HostLoop> loops, List<HostDatagramSocket> sockets, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor, V3Listener v3Requested) throws Exception {
        V3Listener v3 = enableV3(v3Requested);
        if (v3 != null) {
            config = config.withMultistream();
        }
        if (sockets.isEmpty() || loops.size() != sockets.size()) {
            throw new IllegalArgumentException("one loop per socket and at least one socket are required, got "
                    + loops.size() + " loops and " + sockets.size() + " sockets");
        }
        Shared shared = new Shared(sockets.size(), identity, config, acceptor, v3);
        SocketDispatcher dispatcher = sockets.size() > 1 ? new SocketDispatcher(shared, sockets.size()) : null;
        List<Channel> channels = new ArrayList<>(sockets.size());
        boolean inLoop = false;
        for (HostLoop loop : loops) {
            inLoop |= loop.inEventLoop(Thread.currentThread());
        }
        try {
            for (int i = 0; i < sockets.size(); i++) {
                HostLoop loop = loops.get(i);
                HostedDatagramChannel channel = new HostedDatagramChannel(sockets.get(i));
                channel.pipeline().addLast(IngressGuard.forConfig(shared.ingress), dispatcher != null
                        ? dispatcher
                        : codec(shared, Codecs.LOCAL_CONNECTION_ID_LENGTH, null, 0));
                io.netty.channel.ChannelFuture registered = new HostedEventLoop(loop).register(channel);
                if (!loop.inEventLoop(Thread.currentThread())) {
                    registered.sync();
                } else if (registered.isDone() && !registered.isSuccess()) {
                    throw new IllegalStateException("registering the QUIC listener failed", registered.cause());
                }
                channels.add(channel);
            }
            return new QuicServer(null, channels, shared);
        } catch (Exception | Error e) {
            // Sockets already registered close with their channel; the one that failed, and any
            // later one, never got one.
            closeQuietly(channels, !inLoop);
            for (int i = channels.size(); i < sockets.size(); i++) {
                try {
                    sockets.get(i).close();
                } catch (Throwable ignored) {
                    // the bind failure is what the caller must see
                }
            }
            throw e;
        }
    }

    /**
     * Closes what a failed bind already bound, ignoring a failure to close one of them. Waits for
     * each close unless {@code wait} is false: a caller inside one of the host loops must not block
     * on its own loop, where the close would never run.
     */
    private static void closeQuietly(List<Channel> channels, boolean wait) {
        for (Channel channel : channels) {
            try {
                if (wait) {
                    channel.close().syncUninterruptibly();
                } else {
                    channel.close();
                }
            } catch (Throwable ignored) {
                // the bind failure is what the caller must see
            }
        }
    }

    /** One per bind, like the Retry key inside the token handler: attempts and open-connection leases. */
    private static ConnectionAdmission admission(TransportConfig config) {
        return new ConnectionAdmission(
                config.globalAttemptsPerSecond, config.perIpAttemptsPerSecond, config.maxOpen,
                config.maxOpenPerIp, config.retryThresholdPerSecond, config.maxTrackedIps);
    }

    /** Set once so a native without the 13.13 window caps logs one INFO line, not one per bind. */
    private static final java.util.concurrent.atomic.AtomicBoolean V3_NATIVE_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Whether this listener can run {@code /3} (13.13): the loaded native must cap quiche's
     * flow-control windows. Without it {@code v3} null is returned, so the bind falls back to the
     * plain {@code /2} listener, one INFO line logged per JVM (12.3), never a half-enabled one.
     */
    private static V3Listener enableV3(V3Listener v3) {
        if (v3 == null) {
            return null;
        }
        if (QuicSupport.hasMaxFlowControlWindows()) {
            return v3;
        }
        if (V3_NATIVE_WARNING.compareAndSet(false, true)) {
            LOG.info("QUIC native cannot cap quiche's flow-control windows; /3 is unavailable and this"
                    + " listener runs as a plain /2 listener (12.3, 13.13)");
        }
        return null;
    }

    /** Deadline for a connection passed early to complete its handshake. Tests shorten it. */
    static volatile long confirmMillis = 10_000;

    /**
     * Tests only: overrides the bidirectional stream limit a {@code /3} listener advertises to the
     * client ({@code -1} keeps 13.13's two). It lets a test reach the "other stream" branch of
     * 13.3, which the two-stream limit otherwise hides behind the QUIC stream count.
     */
    static volatile int v3StreamsForTests = -1;

    /**
     * Deadline for every connection to complete its handshake, so spoofed or abandoned handshakes
     * can't hold open-connection slots. Separate from {@link #confirmMillis}. Tests shorten it.
     */
    static volatile long handshakeMillis = 10_000;

    /**
     * Everything one listener keeps node-wide, so N sockets never multiply it: admission and its
     * budgets, the Retry key and its token handler, early tokens, the SSL context, the stream
     * acceptor and the per-socket accepted-connection counters. Built once per bind, shared by
     * every socket's codec.
     */
    private static final class Shared {
        final TransportConfig config;
        final StreamAcceptor acceptor;
        /** Null for a {@code /2} listener; non-null enables the {@code /3} listener (13.1). */
        final V3Listener v3;
        final Unconfirmed unconfirmed = new Unconfirmed();
        final java.util.concurrent.atomic.AtomicLong retries = new java.util.concurrent.atomic.AtomicLong();
        /** Token-less attempts and open-connection leases; synchronized, safe from every loop. */
        final ConnectionAdmission admission;
        /**
         * The node's receive budget (13.12): admission reserves each /3 connection's initial credit here
         * and every lane session reserves its retained bytes here. Unlimited without a budget.
         */
        final ReceiveBudget nodeBudget;
        /** /3 staged pre-login admission (12.13); null for /2 or without a resource pool. */
        final PreLoginRegistry preLogin;
        /** The N+2 sessions' pools, ranking and shed dispatch, one per listener (13.12); null for /2. */
        final SessionResources sessionResources;
        /** The ingress guard's datagram budget, shared by every socket's guard. */
        final ConnectionAdmission ingress;
        /** One instance for every socket's codec: it holds the listener's Retry key. */
        final AdmissionTokenHandler tokenHandler;
        final EarlyTokens tokens = new EarlyTokens();
        final QuicSslContext ssl;
        /** Connections accepted per socket, in bind order; null with a single socket. */
        final AtomicIntegerArray accepted;
        /**
         * Datagrams the forward bound dropped per target socket, in bind order; null with a single
         * socket, which never forwards (docs/protocol.md §11).
         */
        final AtomicIntegerArray droppedForwards;
        /**
         * Datagrams each socket dropped because they were a short header too short for the whole
         * connection id, in bind order; null with a single socket, which never parses an index
         * (docs/protocol.md §11).
         */
        final AtomicIntegerArray droppedMalformed;

        Shared(int sockets, ServerIdentity identity, TransportConfig config, StreamAcceptor acceptor, V3Listener v3)
                throws Exception {
            this.config = config;
            this.nodeBudget = config.nodeBudget != null ? config.nodeBudget
                    : config.nodePendingBytes > 0 ? ReceiveBudget.bounded(config.nodePendingBytes)
                            : ReceiveBudget.unlimited();
            this.preLogin = v3 != null && config.resourcePools != null
                    ? new PreLoginRegistry(config.resourcePools, config.sessionCallsPerPrefix,
                            config.declaredBytesPerPrefix, new java.util.Random()) : null;
            this.sessionResources = v3 != null ? SessionResources.production(config.resourcePools) : null;
            this.acceptor = acceptor;
            this.v3 = v3;
            this.accepted = sockets > 1 ? new AtomicIntegerArray(sockets) : null;
            this.droppedForwards = sockets > 1 ? new AtomicIntegerArray(sockets) : null;
            this.droppedMalformed = sockets > 1 ? new AtomicIntegerArray(sockets) : null;
            this.admission = admission(config);
            this.ingress = IngressGuard.budget(config);
            // Attempts with a valid token get their own budget; its open caps are unused.
            ConnectionAdmission validated = new ConnectionAdmission(
                    config.globalAttemptsPerSecond, config.perIpAttemptsPerSecond, 1, 1,
                    config.retryThresholdPerSecond, config.maxTrackedIps);
            if ((long) config.maxTrackedIps < (long) config.maxOpen + config.globalAttemptsPerSecond) {
                LOG.warning("QUIC admission: maxTrackedIps (" + config.maxTrackedIps + ") is below maxOpen + "
                        + "globalAttemptsPerSecond (" + config.maxOpen + " + " + config.globalAttemptsPerSecond
                        + "); new addresses may be refused while the tracked ones fill it");
            }
            if (config.maxTrackedIps < config.ingressInitialsPerSecond) {
                LOG.warning("QUIC admission: maxTrackedIps (" + config.maxTrackedIps + ") is below "
                        + "ingressInitialsPerSecond (" + config.ingressInitialsPerSecond + "), and the ingress guard "
                        + "shares that tracking limit; Initials from new addresses may be dropped while it is full");
            }
            this.tokenHandler = new AdmissionTokenHandler(admission, validated, config.retryMode, retries, v3 != null);
            this.ssl = QuicSslContextBuilder
                    .forServer(identity.privateKey(), null, identity.certificate())
                    // A /3 listener is /3-only: a /2 client fails the ALPN and falls back to TCP.
                    .applicationProtocols(v3 != null ? Protocol.ALPN_V3 : Protocol.ALPN)
                    .earlyData(config.earlyData)
                    .build();
        }
    }

    /**
     * One dispatcher per listener, shared (Netty's {@link QuicCodecDispatcher} is {@code @Sharable})
     * by every socket's pipeline: it builds each socket's codec with the connection id generator
     * that encodes that socket's index into every id the codec issues, and forwards a datagram
     * whose destination connection id names another socket to that socket's codec.
     *
     * <p>Every socket also gets a {@link ForwardLimiter} in front of this dispatcher, which bounds
     * what it may hand over to another socket's loop. Netty's {@code channelRead} is {@code final},
     * so the bound is a handler of its own: it decodes the same index and either releases the
     * datagram or passes it on untouched. The index it uses is exact: the base class builds the id
     * generator with the socket's index and hands that generator to {@link #initChannel}, so the
     * per-socket counters and the bound agree with the ids however concurrent boots interleave.
     */
    private static final class SocketDispatcher extends QuicCodecDispatcher {
        /** Pipeline name of the bound in front of this dispatcher, one per socket. */
        private static final String LIMITER = "quicraft-forward-bound";

        private final Shared shared;
        /** In-flight cross-loop hand-overs per target socket, shared by every socket's limiter. */
        private final AtomicIntegerArray pending;
        /** Each socket's limiter, indexed by the index the base class gave that socket. */
        private final AtomicReferenceArray<ChannelHandlerContext> limiterOfSocket;
        /** Not expected to be used; see {@link IndexedQuicConnectionIdGenerator#indexOf}. */
        private final java.util.concurrent.atomic.AtomicInteger added =
                new java.util.concurrent.atomic.AtomicInteger();
        /** When this listener last logged a swallowed datagram exception, in millis; one per listener. */
        private final java.util.concurrent.atomic.AtomicLong lastSwallowedLog =
                new java.util.concurrent.atomic.AtomicLong();

        SocketDispatcher(Shared shared, int sockets) {
            super(Codecs.LOCAL_CONNECTION_ID_LENGTH);
            this.shared = shared;
            this.pending = new AtomicIntegerArray(sockets);
            this.limiterOfSocket = new AtomicReferenceArray<ChannelHandlerContext>(sockets);
        }

        /** Carries the index the base class encodes into every id of this socket's codec. */
        @Override
        protected QuicConnectionIdGenerator newIdGenerator(int idx) {
            return new IndexedQuicConnectionIdGenerator(idx, super.newIdGenerator(idx));
        }

        @Override
        protected void initChannel(Channel channel, int localConnectionIdLength, QuicConnectionIdGenerator idGenerator)
                throws Exception {
            int socketIndex = IndexedQuicConnectionIdGenerator.indexOf(idGenerator);
            if (socketIndex < 0) {
                // Only this class builds these generators, so this is not expected; keep a stable
                // index rather than failing a bind over it.
                socketIndex = added.getAndIncrement();
            }
            ForwardLimiter limiter = new ForwardLimiter(socketIndex, localConnectionIdLength, pending,
                    shared.droppedForwards, shared.droppedMalformed, limiterOfSocket);
            // In front of this dispatcher: it sees a datagram first, and whatever it passes on is
            // dispatched exactly as before. The dispatcher is already in the pipeline here, because
            // the base class calls this from its own handlerAdded.
            String dispatcher = channel.pipeline().context(this).name();
            channel.pipeline().addBefore(dispatcher, LIMITER + "-" + socketIndex, limiter);
            channel.pipeline().addLast(codec(shared, localConnectionIdLength, idGenerator, socketIndex));
            // Last, after the codec: nothing else on a datagram pipeline catches an exception.
            channel.pipeline().addLast(new DatagramExceptionBackstop(lastSwallowedLog, socketIndex));
        }
    }

    /**
     * Bounds what one socket's loop may hand over to another socket's loop (docs/protocol.md §11).
     *
     * <p>Netty's {@link QuicCodecDispatcher} delivers a datagram to another socket by firing a read
     * on that socket's context, which from another loop means one queued task per datagram and has
     * no bound. A client that sends short-header datagrams whose destination connection id names
     * one socket (two bytes {@code 00 00}) from many source ports would otherwise grow that
     * socket's task queue without bound: heap growth, and stalls on the loops that also serve TCP
     * players. Netty's {@code channelRead} is {@code final}, so this bound is a handler of its own,
     * installed in front of the dispatcher; a datagram it does not hand over itself (a long header,
     * a socket that is not registered yet) is passed on unchanged, so the dispatcher still decodes
     * it exactly as before.
     *
     * <p>The one datagram it does not pass on is a short header that stops before the whole
     * connection id, including a single byte and {@code 40 00}: Netty's dispatcher reads the index
     * out of that id and its parser throws when there are too few bytes
     * ({@code QuicHeaderParser.sliceCid}), and no handler on a datagram pipeline catches that, so the
     * tail would log a stack trace per datagram, which any remote sender can trigger, and the buffer
     * the throwing handler never released would leak. Such a datagram can never be decoded or
     * delivered, so it is released here and counted in {@link Shared#droppedMalformed} against the
     * socket that read it, and never reaches the dispatcher.
     *
     * <p>At most {@link #MAX_PENDING_FORWARDS} datagrams may be waiting on one target socket, counted
     * across all source sockets. Over that, the datagram is released here and counted in
     * {@link Shared#droppedForwards}, never queued. Two source loops can both find room and overshoot
     * by at most one each, so this is a bound, not an exact count. Same-loop delivery is left to the
     * dispatcher, which fires it directly.
     *
     * <p>A hand-over also has to close the target socket's read cycle. Netty's dispatcher delivers
     * a forwarded datagram by firing a read on the target's codec context, and records that it owes
     * that context a read-complete
     * ({@code QuicCodecDispatcher.ChannelHandlerContextDispatcher} sets its flag in
     * {@code fireChannelRead} and fires {@code fireChannelReadComplete()} from
     * {@code channelReadComplete}); the QUIC codec only flushes and completes a receive there
     * ({@code QuicheQuicCodec.channelReadComplete()} calls {@code recvComplete()}). Nothing on the
     * target socket fires that completion for a datagram it never read itself, so a connection
     * whose packets all arrive on a foreign socket, as they do after a NAT rebind, would never be
     * flushed and could stall with the datagrams already accounted for. The forwarding task
     * therefore fires {@code fireChannelReadComplete()} on the target's context once the in-flight
     * count for that target reaches zero: the last datagram of a burst closes the read that the
     * burst opened, giving one completion per burst rather than one per datagram, and the next
     * burst's own last task fires its own. Sustained forwarding can keep the count above zero
     * indefinitely, so every {@link #COMPLETE_EVERY_FORWARDS}th forwarded read fires a completion
     * as well.
     */
    static final class ForwardLimiter extends ChannelInboundHandlerAdapter {
        /** Datagrams one loop may have handed to another and not seen processed, per target. */
        static final int MAX_PENDING_FORWARDS = 1024;
        /**
         * Forwarded reads after which the target's read completion fires even though more are still
         * in flight, so sustained cross-socket traffic that never lets the count reach zero still
         * flushes.
         */
        static final int COMPLETE_EVERY_FORWARDS = 64;
        /** {@link #targetSocket}: a short header that stops before the whole connection id. */
        private static final int MALFORMED = -2;

        /** The index the listener's ids encode for this socket; indexes {@link #limiterOfSocket}. */
        private final int socketIndex;
        /** {@link Codecs#LOCAL_CONNECTION_ID_LENGTH}: how many bytes a connection id takes. */
        private final int localConnectionIdLength;
        /** In-flight hand-overs per target socket, shared by every socket's limiter. */
        private final AtomicIntegerArray pending;
        /** Drops per target socket, shared by every socket's limiter. */
        private final AtomicIntegerArray dropped;
        /** Malformed drops per receiving socket, shared by every socket's limiter. */
        private final AtomicIntegerArray droppedMalformed;
        /** The limiter of each socket, so a datagram can be handed to that socket's loop. */
        private final AtomicReferenceArray<ChannelHandlerContext> limiterOfSocket;
        /**
         * Datagrams handed to this socket since its last forwarded read completion. Touched only by
         * the forwarding tasks, which all run on this socket's own loop.
         */
        private int forwardedSinceComplete;

        ForwardLimiter(int socketIndex, int localConnectionIdLength, AtomicIntegerArray pending,
                AtomicIntegerArray dropped, AtomicIntegerArray droppedMalformed,
                AtomicReferenceArray<ChannelHandlerContext> limiterOfSocket) {
            this.socketIndex = socketIndex;
            this.localConnectionIdLength = localConnectionIdLength;
            this.pending = pending;
            this.dropped = dropped;
            this.droppedMalformed = droppedMalformed;
            this.limiterOfSocket = limiterOfSocket;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            limiterOfSocket.set(socketIndex, ctx);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            // The socket is gone: stop handing datagrams to its loop.
            limiterOfSocket.compareAndSet(socketIndex, ctx, null);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramPacket) {
                int target = targetSocket((DatagramPacket) msg);
                if (target == MALFORMED) {
                    // A short header this listener can never decode, and one the dispatcher's own
                    // parse throws for: drop it here (see the class javadoc) rather than let the
                    // whole pipeline log a stack trace and leak the buffer.
                    dropMalformed(msg);
                    return;
                }
                // Out of range is not a socket: hand it to the dispatcher, which answers for it the
                // same way (an id naming a socket that does not exist goes to the local codec).
                ChannelHandlerContext to = target < 0 || target >= limiterOfSocket.length()
                        ? null : limiterOfSocket.get(target);
                if (to != null && !to.executor().inEventLoop()) {
                    forward(target, to, msg);
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }

        /**
         * The index the datagram's destination connection id names, decoded exactly as
         * {@link QuicCodecDispatcher} decodes it (the first two bytes of the id of a short-header
         * packet, RFC 9000 §17.3); {@link #MALFORMED} for a short header that stops before the whole
         * connection id, which the dispatcher's parser throws for; or -1 for a datagram this bound
         * must not touch, and the dispatcher has to see: a long header, or one whose first byte it
         * cannot read at all.
         */
        private int targetSocket(DatagramPacket packet) {
            ByteBuf content = packet.content();
            int readable = content.readableBytes();
            if (readable == 0) {
                // No header byte to classify, and not a datagram this bound may drop for anyone.
                return -1;
            }
            if ((content.getByte(content.readerIndex()) & 0x80) != 0) {
                // A long header, an Initial for example: it carries no index.
                return -1;
            }
            if (readable < Byte.BYTES + localConnectionIdLength) {
                return MALFORMED;
            }
            return content.getUnsignedShort(content.readerIndex() + Byte.BYTES);
        }

        /**
         * Hands {@code msg} to {@code to}'s loop, unless that loop already has
         * {@link #MAX_PENDING_FORWARDS} of them waiting, and then releases it here instead of
         * queueing it. The hand-over runs through the target's own executor, so the read on its
         * context happens on its loop, and the pending count drops in a {@code finally} whether the
         * read throws or an early shutdown rejects the task. The task that drops the count to zero,
         * or the {@link #COMPLETE_EVERY_FORWARDS}th since the last one, also fires the target's read
         * completion (see the class javadoc): the dispatcher delivers
         * the read without one, and the QUIC codec flushes and completes the receive only from
         * {@code channelReadComplete}.
         */
        private void forward(int target, ChannelHandlerContext to, Object msg) {
            if (pending.get(target) >= MAX_PENDING_FORWARDS) {
                drop(target, msg);
                return;
            }
            pending.incrementAndGet(target);
            try {
                to.executor().execute(() -> {
                    try {
                        to.fireChannelRead(msg);
                    } finally {
                        boolean drained = pending.decrementAndGet(target) == 0;
                        ForwardLimiter owner = (ForwardLimiter) to.handler();
                        if (drained || ++owner.forwardedSinceComplete >= COMPLETE_EVERY_FORWARDS) {
                            owner.forwardedSinceComplete = 0;
                            readComplete(target, to);
                        }
                    }
                });
            } catch (RuntimeException rejected) {
                // Shutting down: do not leave the slot pending for ever, and do not leak the buffer.
                pending.decrementAndGet(target);
                drop(target, msg);
            }
        }

        /**
         * Closes the read cycle the target's dispatcher opened for the forwarded datagram, once no
         * more of it is in flight. Fired from the target's loop, on the limiter's own context so it
         * reaches the dispatcher and from there the codec the dispatcher selected. Never lets a
         * throwing completion (a socket torn down mid-burst, say) affect the counter, which those
         * tasks own.
         */
        private void readComplete(int target, ChannelHandlerContext to) {
            try {
                to.fireChannelReadComplete();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "firing the read completion for forwarded datagrams on socket " + target
                        + " failed", t);
            }
        }

        private void drop(int target, Object msg) {
            ReferenceCountUtil.release(msg);
            dropped.incrementAndGet(target);
        }

        /**
         * Releases a datagram that can never be decoded and counts it against the socket that read
         * it (the only socket that could know about it: a short header truncated before the
         * connection id has no readable index).
         */
        private void dropMalformed(Object msg) {
            ReferenceCountUtil.release(msg);
            droppedMalformed.incrementAndGet(socketIndex);
        }
    }

    /**
     * The last handler on every socket of a multi-socket listener: swallows what no other handler on
     * a datagram pipeline would.
     *
     * <p>A datagram pipeline has no {@code exceptionCaught} of its own, and Netty's dispatcher
     * parses the destination connection id of every short-header datagram, so a datagram its parser
     * throws for reaches Netty's tail, which logs a stack trace per datagram and releases nothing.
     * The bound in front of the dispatcher already drops the truncated short headers it can
     * recognise (see {@link ForwardLimiter}); this is the backstop for whatever else a datagram
     * handler throws, so a remote sender can never turn one packet into one logged stack trace, and
     * at most one FINE line per minute per listener is logged instead. Installed by
     * {@link SocketDispatcher#initChannel}, so both the core-owned and the hosted path get it.
     *
     * <p>One instance per socket, all sharing the listener's timestamp of its last swallowed log, so
     * the rate limit is the listener's however many sockets it has.
     */
    static final class DatagramExceptionBackstop extends ChannelInboundHandlerAdapter {
        /** At most one log line per listener per this period. */
        static final long LOG_INTERVAL_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(1);

        /** This listener's last swallowed-exception log, in millis; shared by all its sockets. */
        private final java.util.concurrent.atomic.AtomicLong lastLog;
        /** The socket this instance sits on, for the log line. */
        private final int socketIndex;

        DatagramExceptionBackstop(java.util.concurrent.atomic.AtomicLong lastLog, int socketIndex) {
            this.lastLog = lastLog;
            this.socketIndex = socketIndex;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            long now = System.currentTimeMillis();
            long last = lastLog.get();
            if (now - last >= LOG_INTERVAL_MILLIS && lastLog.compareAndSet(last, now)) {
                LOG.log(Level.FINE, "a datagram on QUIC socket " + socketIndex + " was dropped: a handler threw "
                        + "for it (at most one of these per minute per listener)", cause);
            }
            // Deliberately not forwarded: Netty's tail would log a stack trace per datagram.
        }
    }

    /**
     * The connection id generator the base class built, plus the index it encodes, so
     * {@link SocketDispatcher#initChannel} knows the socket's index without counting calls
     * (which concurrent boots could interleave differently from the base class's own order).
     * Every call goes straight through, so the ids are exactly the ones the base class produces.
     */
    private static final class IndexedQuicConnectionIdGenerator implements QuicConnectionIdGenerator {
        private final int index;
        private final QuicConnectionIdGenerator delegate;

        IndexedQuicConnectionIdGenerator(int index, QuicConnectionIdGenerator delegate) {
            this.index = index;
            this.delegate = delegate;
        }

        /** The index of {@code generator}, or -1 if it is not one of ours. */
        static int indexOf(QuicConnectionIdGenerator generator) {
            if (generator instanceof IndexedQuicConnectionIdGenerator) {
                return ((IndexedQuicConnectionIdGenerator) generator).index;
            }
            return -1;
        }

        @Override
        public ByteBuffer newId(int length) {
            return delegate.newId(length);
        }

        @Override
        public ByteBuffer newId(ByteBuffer input, int length) {
            return delegate.newId(input, length);
        }

        @Override
        public ByteBuffer newId(ByteBuffer scid, ByteBuffer dcid, int length) {
            return delegate.newId(scid, dcid, length);
        }

        @Override
        public int maxConnectionIdLength() {
            return delegate.maxConnectionIdLength();
        }

        @Override
        public boolean isIdempotent() {
            return delegate.isIdempotent();
        }
    }

    /**
     * The codec of one UDP socket over the listener's {@link Shared} state.
     * {@code localConnectionIdLength} and {@code idGenerator} come from the socket's dispatcher
     * when the listener has several sockets, and are the single-socket values (the constant, no
     * generator) otherwise; {@code socketIndex} indexes {@link Shared#accepted}.
     */
    private static io.netty.channel.ChannelHandler codec(Shared shared, int localConnectionIdLength,
            QuicConnectionIdGenerator idGenerator, int socketIndex) throws Exception {
        QuicServerCodecBuilder builder = Codecs.apply(new QuicServerCodecBuilder(), shared.config, true)
                .sslContext(shared.ssl)
                // Attempt limits and address validation (Retry), docs/protocol.md §9.
                .tokenHandler(shared.tokenHandler)
                .localConnectionIdLength(localConnectionIdLength)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel connection) {
                        Runnable install = () -> {
                            if (shared.accepted != null) {
                                // Written only by this socket's own loop, so no synchronization.
                                shared.accepted.incrementAndGet(socketIndex);
                            }
                            connection.pipeline().addLast(new Connection(shared.acceptor, shared.tokens,
                                    shared.config.earlyData, shared.unconfirmed, shared.v3, shared.nodeBudget,
                                    shared.sessionResources));
                        };
                        Admitted admitted = Admitted.admit(connection, shared.admission, shared.v3 != null
                                ? shared.nodeBudget : null, shared.preLogin, shared.config.preLoginOnline, install,
                                stream -> initStream(stream, shared));
                        if (admitted == null) {
                            return;
                        }
                        connection.pipeline().addLast(admitted);
                        if (admitted.isReady()) {
                            admitted.grant();
                        }
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        if (stream.parent().hasAttr(REFUSED)) {
                            return; // closing with CONNECTION_REFUSED already
                        }
                        Admitted admitted = stream.parent().pipeline().get(Admitted.class);
                        if (admitted != null && admitted.isPending()) {
                            admitted.hold(stream); // initialized once the newcomer is granted
                            return;
                        }
                        initStream(stream, shared);
                    }
                });
        if (idGenerator != null) {
            builder.connectionIdAddressGenerator(idGenerator);
        }
        if (shared.v3 != null && v3StreamsForTests >= 0) {
            builder.initialMaxStreamsBidirectional(v3StreamsForTests);
        }
        return builder.build();
    }

    /** Installs the handlers of one stream of an admitted connection (the codec's stream initializer). */
    private static void initStream(QuicStreamChannel stream, Shared shared) {
        Connection connection = stream.parent().pipeline().get(Connection.class);
        if (connection == null || stream.type() != QuicStreamType.BIDIRECTIONAL
                || stream.isLocalCreated()) {
            Codecs.closeLater(stream.parent(), true, Protocol.CLOSE_PROTOCOL_VIOLATION);
            return;
        }
        if (shared.v3 != null) {
            rs.sudoe.quicraft.core.multistream.ReadGrantAllocator.install(stream);
            // /3 (13.3): stream 0 is CONTROL, the client's stream 4 is REALTIME, and
            // any other stream is fatal 0x11.
            if (stream.streamId() == 0) {
                connection.offerV3(stream);
            } else {
                connection.acceptV3(stream);
            }
            return;
        }
        connection.offer(new NettyQuicByteStream(stream));
    }

    /**
     * Per connection, first in its pipeline: holds its open-connection lease and its pre-login charge, confirms the
     * lease when the handshake completes, releases both on close, and closes a connection whose handshake hasn't
     * completed within {@link #handshakeMillis}. A validated /3 newcomer that must wait for a shed victim's close
     * (12.13) is pending: the game connection and its streams are held until the newcomer is granted, and the
     * newcomer is refused at its staged deadline.
     */
    private static final class Admitted extends ChannelInboundHandlerAdapter {
        /** The waiting-streams state of one stream that arrived while pending. */
        private static final class Held {
            final QuicStreamChannel stream;
            final boolean autoRead;

            Held(QuicStreamChannel stream, boolean autoRead) {
                this.stream = stream;
                this.autoRead = autoRead;
            }
        }

        private final QuicChannel connection;
        private final ConnectionAdmission admission;
        private final ReceiveBudget nodeBudget;
        private final PreLoginRegistry preLogin;
        private final boolean online;
        /** Installs the game connection once the newcomer is granted. */
        private final Runnable install;
        /** Initializes a stream that arrived while the newcomer was pending. */
        private final java.util.function.Consumer<QuicStreamChannel> initStream;

        /** Null when admission failed open, or not yet acquired. */
        private ConnectionAdmission.Lease lease;
        private PreLoginRegistry.Entry staged;
        private boolean pending;
        private boolean ready;
        private boolean refused;
        private boolean closed;
        private boolean handshakeDone;
        /** The handshake's completion, kept so a game connection installed after a pending wait can be told. */
        private io.netty.handler.ssl.SslHandshakeCompletionEvent handshakeEvent;
        /** When the connection was first accepted: the 30 s hard cap (12.13) runs from here, through every retry. */
        private final long acceptedAt = System.nanoTime();
        private final List<Held> held = new ArrayList<>();
        private java.util.concurrent.ScheduledFuture<?> deadline;
        private java.util.concurrent.ScheduledFuture<?> pendingDeadline;
        private java.util.concurrent.ScheduledFuture<?> stagedCheck;

        /** Bytes this connection reserved against the node budget, 0 when none. */
        private long reserved;
        private ReceiveBudget reservedPool;

        private Admitted(QuicChannel connection, ConnectionAdmission admission, ReceiveBudget nodeBudget,
                PreLoginRegistry preLogin, boolean online, Runnable install,
                java.util.function.Consumer<QuicStreamChannel> initStream) {
            this.connection = connection;
            this.admission = admission;
            this.nodeBudget = nodeBudget;
            this.preLogin = preLogin;
            this.online = online;
            this.install = install;
            this.initStream = initStream;
        }

        /**
         * Runs admission: takes an open-connection lease, then charges the pre-login pool (or the node budget
         * without it). Runs inside the codec's read (the channel is registered there), where the remote address is
         * already set. Returns null when the connection is refused (already closing). Otherwise the result is ready
         * ({@link #isReady}, call {@link #grant} once in the pipeline) or pending (waiting for a shed close).
         */
        static Admitted admit(QuicChannel connection, ConnectionAdmission admission, ReceiveBudget nodeBudget,
                PreLoginRegistry preLogin, boolean online, Runnable install,
                java.util.function.Consumer<QuicStreamChannel> initStream) {
            Admitted admitted = new Admitted(connection, admission, nodeBudget, preLogin, online, install, initStream);
            connection.closeFuture().addListener(f -> admitted.closed());
            admitted.step();
            return admitted.refused ? null : admitted;
        }

        /** Runs the admission steps still missing. Each step either proceeds, waits (pending) or refuses. */
        private void step() {
            if (preLogin != null && System.nanoTime() - acceptedAt >= PreLoginTracker.HARD_CAP_NANOS) {
                // 12.13: the 30 s hard cap runs from the first accepted packet, through every retry.
                LOG.log(Level.FINE, "QUIC /3 connection refused: its pre-login wait ran out");
                refuse(true, Protocol.CLOSE_RESOURCE);
                return;
            }
            InetAddress source = sourceOf(connection);
            if (lease == null && source != null) {
                try {
                    lease = admission.tryAcquire(source);
                    if (lease == null && preLogin != null) {
                        // 12.13: a validated /3 newcomer over the open-connection limits sheds a pre-login victim
                        // instead of being dropped. The victim's lease is returned when its close is confirmed.
                        if (preLogin.shedForLease(source, STAGED_LANES, connection, this::wake).pending) {
                            waiting();
                            return;
                        }
                    }
                    if (lease == null) {
                        // Last resort: AdmissionTokenHandler checked the same limits on this thread
                        // just before the connection was created, so this should not happen. It
                        // can't fall back cleanly: Netty sends the queued handshake flight before the
                        // close (QuicheQuicChannel.java:615-616 in Netty 4.2.19, "send all that is
                        // queued before we close"), so the client completes its handshake and then
                        // sees the close, which reaches the game.
                        LOG.log(Level.FINE, "QUIC connection over the open-connection limits after admission; "
                                + "refusing it");
                        refuse(false, CONNECTION_REFUSED);
                        return;
                    }
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "QUIC open-connection check failed; admitting the connection", t);
                }
            }
            if (preLogin != null) {
                // 12.13: /3 is staged from its first accepted Initial, charged to the pre-login pool
                // instead of the node budget until promotion (Login Success online, activation offline).
                if (staged == null) {
                    if (source == null) {
                        refuse(true, Protocol.CLOSE_RESOURCE);
                        return;
                    }
                    PreLoginRegistry.Admission attempt = preLogin.attempt(source, connection, STAGED_LANES, online,
                            System::nanoTime, acceptedAt, this::shedClose, this::wake);
                    if (attempt.pending) {
                        waiting();
                        return;
                    }
                    if (attempt.entry == null) {
                        LOG.log(Level.FINE, "QUIC /3 connection refused: the pre-login pool has no room");
                        refuse(true, Protocol.CLOSE_RESOURCE);
                        return;
                    }
                    staged = attempt.entry;
                }
            } else if (nodeBudget != null && reservedPool == null) {
                // 13.12: the connection's initial receive-credit exposure counts against the node
                // budget, and one that does not fit is refused (TCP is untouched).
                long exposure = TransportConfig.V3_INITIAL_EXPOSURE_BYTES;
                if (!nodeBudget.tryReserve(exposure)) {
                    LOG.log(Level.FINE, "QUIC /3 connection over the node pending-bytes budget; refusing it");
                    refuse(false, CONNECTION_REFUSED);
                    return;
                }
                reserved = exposure;
                reservedPool = nodeBudget;
            }
            pending = false;
            ready = true;
            cancel(pendingDeadline);
            pendingDeadline = null;
        }

        /** The remote IP, or null when the connection has none (admission then fails open or refuses). */
        private static InetAddress sourceOf(QuicChannel connection) {
            try {
                java.net.SocketAddress remote = connection.remoteSocketAddress();
                if (remote instanceof InetSocketAddress) {
                    return ((InetSocketAddress) remote).getAddress();
                }
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "QUIC remote address unavailable", e);
            }
            return null;
        }

        private void waiting() {
            pending = true;
            if (pendingDeadline == null) {
                // The newcomer's staged deadline: a victim that never closes must not hold it forever.
                long remaining = Math.max(0L, acceptedAt + PreLoginTracker.HARD_CAP_NANOS - System.nanoTime());
                pendingDeadline = connection.eventLoop().schedule(() -> {
                    if (pending && !closed) {
                        LOG.log(Level.FINE, "QUIC /3 connection refused: its pre-login wait ran out");
                        refuse(true, Protocol.CLOSE_RESOURCE);
                    }
                }, remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }

        /** A shed victim's close was confirmed: retries this newcomer on its own event loop, never inline. */
        private void wake() {
            Runnable retry = () -> {
                if (pending && !closed) {
                    step();
                    if (ready) {
                        grant();
                    }
                }
            };
            java.util.Queue<Runnable> hold = heldWakes;
            if (hold != null) {
                // Test seam: the dispatch waits in the queue until the test runs it.
                hold.add(() -> connection.eventLoop().execute(retry));
                return;
            }
            connection.eventLoop().execute(retry);
        }

        private void refuse(boolean stagedRefusal, int code) {
            refused = true;
            pending = false;
            cancel(pendingDeadline);
            pendingDeadline = null;
            connection.attr(REFUSED).set(Boolean.TRUE);
            if (preLogin != null) {
                preLogin.settle(connection);
            }
            Codecs.closeLater(connection, stagedRefusal, code);
        }

        /** The pool shed's close (12.13). Non-blocking; the charge and the lease go when the close is confirmed. */
        private void shedClose() {
            Codecs.closeLater(connection, true, Protocol.CLOSE_RESOURCE);
        }

        boolean isReady() {
            return ready;
        }

        boolean isPending() {
            return pending;
        }

        /** Holds a stream that arrived while pending: no reads until it is initialized on the grant. */
        void hold(QuicStreamChannel stream) {
            boolean autoRead = stream.config().isAutoRead();
            stream.config().setAutoRead(false);
            held.add(new Held(stream, autoRead));
        }

        /**
         * Grants a ready newcomer on its own loop: the game connection is installed behind this handler, its staged
         * state starts its checks, and any streams held while pending are initialized.
         */
        void grant() {
            ready = false;
            if (staged != null) {
                connection.attr(PRE_LOGIN).set(staged);
                scheduleStagedCheck(connection.eventLoop(), connection);
            }
            if (handshakeDone && lease != null) {
                lease.confirm();
            }
            install.run();
            if (handshakeEvent != null) {
                // The handshake completed while this connection waited: the game connection never saw the event.
                connection.pipeline().context(this).fireUserEventTriggered(handshakeEvent);
            }
            List<Held> streams = new ArrayList<>(held);
            held.clear();
            for (Held h : streams) {
                initStream.accept(h.stream);
                h.stream.config().setAutoRead(h.autoRead);
                h.stream.read();
            }
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            QuicChannel connection = (QuicChannel) ctx.channel();
            try {
                rs.sudoe.quicraft.core.Faults.check(rs.sudoe.quicraft.core.Faults.HANDSHAKE_DEADLINE);
                deadline = ctx.executor().schedule(() -> {
                    if (!handshakeDone) {
                        // Transport NO_ERROR: a real but slow client sees a failed handshake and
                        // falls back to TCP.
                        Codecs.closeLater(connection, false, 0x0);
                    }
                }, handshakeMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                // E.g. RejectedExecutionException from a hosted loop shutting down. The handler
                // stays, so the lease is still confirmed and released.
                LOG.log(Level.FINE, "scheduling the QUIC handshake deadline failed; this connection has none", t);
            }
        }

        /**
         * §12.13: once a second, closes a still-staged connection whose client-wait deadline or 30 s hard cap
         * has passed, locally with 0x12. Stops when the connection leaves the pre-login stage.
         */
        private void scheduleStagedCheck(io.netty.util.concurrent.EventExecutor executor, QuicChannel connection) {
            try {
                stagedCheck = executor.scheduleAtFixedRate(() -> {
                    if (staged.tracker().state() != PreLoginTracker.State.STAGED) {
                        stagedCheck.cancel(false);
                        return;
                    }
                    // 12.13: the hard cap is checked before any retry, so an expired connection closes.
                    if (staged.expired()) {
                        stagedCheck.cancel(false);
                        Codecs.closeLater(connection, true, Protocol.CLOSE_RESOURCE);
                        return;
                    }
                    // 12.13: a promotion that the node budget refused is retried on every tick.
                    staged.retryPromotion();
                    if (staged.tracker().state() != PreLoginTracker.State.STAGED) {
                        stagedCheck.cancel(false);
                    }
                }, STAGED_CHECK_MILLIS, STAGED_CHECK_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "scheduling the /3 pre-login deadline failed; this connection has none", t);
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()
                    && !handshakeDone) {
                handshakeDone = true;
                handshakeEvent = (SslHandshakeCompletionEvent) evt;
                if (deadline != null) {
                    deadline.cancel(false);
                }
                if (lease != null && !pending && !ready) {
                    lease.confirm();
                }
            }
            ctx.fireUserEventTriggered(evt);
        }

        private static void cancel(java.util.concurrent.ScheduledFuture<?> task) {
            if (task != null) {
                task.cancel(false);
            }
        }

        private void closed() {
            closed = true;
            pending = false;
            ready = false;
            cancel(deadline);
            cancel(pendingDeadline);
            pendingDeadline = null;
            cancel(stagedCheck);
            held.clear();
            releaseCharges(lease, staged);
            lease = null;
            if (preLogin != null) {
                preLogin.settle(connection);
            }
            if (reserved > 0) {
                reservedPool.release(reserved);
                reserved = 0;
            }
        }
    }

    /**
     * Gives back a closed connection's open-connection lease, then its pre-login charge. The order is the contract:
     * a shed victim's close wakes its newcomer, which must find the slot already free (12.13).
     */
    static void releaseCharges(ConnectionAdmission.Lease lease, PreLoginRegistry.Entry staged) {
        if (lease != null) {
            lease.close();
        }
        if (staged != null) {
            staged.close();
        }
    }

    /**
     * Test seam (package-private, null in production): while set, each pending newcomer's wake is queued here instead
     * of being dispatched to its event loop, so a test decides when the newcomer retries.
     */
    static volatile java.util.Queue<Runnable> heldWakes;

    /**
     * §12.13: the pre-login accounting of a {@code /3} connection, or null for {@code /2} connections and
     * listeners without a resource pool. The platform calls {@code onLoginSuccess} (online) or
     * {@code onActivation} (offline) on it and brackets session-server and platform waits with
     * {@code pauseClientWait}/{@code resumeClientWait}; the V3 read path charges declared frames and early
     * data through it.
     */
    public static PreLoginConnection preLoginTracker(QuicChannel connection) {
        return connection.attr(PRE_LOGIN).get();
    }

    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channels.get(0).localAddress();
    }

    @Override
    public void close() {
        if (group != null) {
            for (Channel channel : channels) {
                channel.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        } else {
            // Hosted: the loop is the platform's. Bounded wait, in case it is already gone.
            for (Channel channel : channels) {
                channel.close().awaitUninterruptibly(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    /**
     * UDP sockets a listener binds by default: {@code min(availableProcessors, 16)} on Linux, where
     * SO_REUSEPORT balances unicast UDP by 4-tuple, and 1 everywhere else (macOS and the BSDs hash
     * only the local address, so one socket would starve, and Windows has no SO_REUSEPORT).
     *
     * <p>System property {@code quicraft.udpSockets} (1..64) overrides the default on every
     * platform, following {@code TransportConfig}'s limit-property pattern; an invalid value logs
     * one WARNING per JVM and falls back to the default. Its value is read per call, so a test (or
     * an embedding launcher) can set it before {@code bind}.
     */
    public static int defaultSocketCount() {
        String value = System.getProperty(UDP_SOCKETS_PROPERTY);
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed >= 1 && parsed <= MAX_UDP_SOCKETS) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // falls through to the warning
            }
            if (UDP_SOCKETS_WARNING.compareAndSet(false, true)) {
                LOG.warning("Invalid " + UDP_SOCKETS_PROPERTY + "=" + value + " (expected 1.." + MAX_UDP_SOCKETS
                        + "); using the default");
            }
        }
        if (!System.getProperty("os.name", "").startsWith("Linux")) {
            return 1;
        }
        return Math.min(Runtime.getRuntime().availableProcessors(), 16);
    }

    /**
     * SO_REUSEPORT as a NIO channel option. Core is Java 8 bytecode and
     * {@code StandardSocketOptions.SO_REUSEPORT} exists only on JDK 9+, so the option is looked up
     * reflectively; the holder caches the JVM's answer. On a JDK or platform without it, no
     * multi-socket bind is attempted.
     */
    private static final class ReusePort {
        static final ChannelOption<Boolean> OPTION = lookup();
        /** False when the JDK knows the option but reports it unsupported for a datagram channel. */
        static final boolean SUPPORTED = OPTION != null && supported(OPTION);

        @SuppressWarnings("unchecked")
        private static ChannelOption<Boolean> lookup() {
            try {
                Object option = Class.forName("java.net.StandardSocketOptions").getField("SO_REUSEPORT").get(null);
                if (option instanceof java.net.SocketOption) {
                    return io.netty.channel.socket.nio.NioChannelOption
                            .of((java.net.SocketOption<Boolean>) option);
                }
            } catch (Throwable unavailable) {
                // JDK 8: the option does not exist (docs/protocol.md §11).
            }
            return null;
        }

        /** Probes a throwaway channel: Netty ignores an option the JDK reports unsupported. */
        private static boolean supported(ChannelOption<Boolean> option) {
            NioDatagramChannel probe = new NioDatagramChannel();
            try {
                return probe.config().setOption(option, Boolean.TRUE);
            } catch (Throwable unsupported) {
                return false;
            } finally {
                probe.unsafe().closeForcibly();
            }
        }
    }

    /**
     * Per-connection state: passes a stream to the game once its preamble is read and either the
     * handshake has completed or the preamble redeemed an early token (docs/protocol.md §8).
     */
    private static final class Connection extends ChannelInboundHandlerAdapter {
        private final StreamAcceptor acceptor;
        private final EarlyTokens tokens;
        private final boolean earlyData;
        private final Unconfirmed unconfirmed;
        /** Non-null enables the {@code /3} stream map of 13.3 on this connection (13.1). */
        private final V3Listener v3;
        private final ReceiveBudget nodeBudget;
        private final SessionResources sessionResources;
        private final List<ServerStream> waiting = new ArrayList<>(1);
        private final List<V3ConnectionImpl> waitingV3 = new ArrayList<>(1);
        /** The /3 connection of stream 0, if one was offered. */
        private V3ConnectionImpl v3Connection;
        private ChannelHandlerContext ctx;
        private boolean handshakeDone;
        private java.util.concurrent.ScheduledFuture<?> confirmDeadline;

        Connection(StreamAcceptor acceptor, EarlyTokens tokens, boolean earlyData, Unconfirmed unconfirmed,
                V3Listener v3, ReceiveBudget nodeBudget, SessionResources sessionResources) {
            this.nodeBudget = nodeBudget;
            this.sessionResources = sessionResources;
            this.acceptor = acceptor;
            this.tokens = tokens;
            this.earlyData = earlyData;
            this.unconfirmed = unconfirmed;
            this.v3 = v3;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        /** Called on the connection's event loop. The stream reads the client's preamble first. */
        void offer(NettyQuicByteStream stream) {
            new ServerStream(stream, this::onPreamble).start();
        }

        /**
         * The {@code /3} stream 0 (13.2, 13.3): its v2 preamble is read first, and the connection
         * is delivered like a {@code /2} stream once the handshake completed or the token redeemed.
         * Called from the stream handler's {@code initChannel}, on the event loop.
         */
        void offerV3(QuicStreamChannel control) {
            V3ConnectionImpl connection = V3ConnectionImpl.server((QuicChannel) ctx.channel(), control,
                    new NettyQuicByteStream(control), v3, this::onPreambleV3, handshakeDone, nodeBudget, sessionResources);
            v3Connection = connection;
            connection.start();
        }

        /**
         * A client stream other than stream 0: exactly stream 4 is allowed, and only after this
         * server's activation (13.3). Called from the stream handler's {@code initChannel}.
         */
        void acceptV3(QuicStreamChannel stream) {
            V3ConnectionImpl connection = v3Connection;
            if (connection == null) {
                // No stream 0 on this connection: nothing may carry lane traffic.
                Codecs.closeLater(stream.parent(), true, MultistreamProtocolException.CLOSE_ORDERING);
                return;
            }
            connection.acceptPeerStream(stream);
        }

        private void onPreamble(ServerStream stream, byte[] token) {
            boolean redeemed = false;
            if (token != null) {
                try {
                    rs.sudoe.quicraft.core.Faults.check(rs.sudoe.quicraft.core.Faults.EARLY_TOKEN_REDEEM);
                    redeemed = tokens.redeem(token);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "early token check failed; the stream waits for the handshake", t);
                }
            }
            if (handshakeDone) {
                deliver(stream);
            } else if (redeemed && earlyData) {
                QuicChannel early = (QuicChannel) ctx.channel();
                Unconfirmed.Entry entry = unconfirmed.add(early, stream.remoteAddress());
                stream.readNameThen(name -> entry.name = name);
                deliver(stream);
                if (confirmDeadline == null) {
                    QuicChannel connection = early;
                    confirmDeadline = ctx.executor().schedule(() -> {
                        if (!handshakeDone) {
                            Codecs.closeLater(connection, true, Protocol.CLOSE_EARLY_UNCONFIRMED);
                        }
                    }, confirmMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } else {
                waiting.add(stream);
            }
        }

        /** The {@code /3} counterpart of {@link #onPreamble}, with the same token and handshake rules. */
        private void onPreambleV3(V3ConnectionImpl connection, byte[] token) {
            boolean redeemed = false;
            if (token != null) {
                try {
                    rs.sudoe.quicraft.core.Faults.check(rs.sudoe.quicraft.core.Faults.EARLY_TOKEN_REDEEM);
                    redeemed = tokens.redeem(token);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "early token check failed; the stream waits for the handshake", t);
                }
            }
            if (handshakeDone) {
                deliverV3(connection);
            } else if (redeemed && earlyData) {
                QuicChannel early = (QuicChannel) ctx.channel();
                Unconfirmed.Entry entry = unconfirmed.add(early, connection.remoteAddress());
                connection.readEarlyName(name -> entry.name = name);
                deliverV3(connection);
                if (confirmDeadline == null) {
                    QuicChannel channel = early;
                    confirmDeadline = ctx.executor().schedule(() -> {
                        if (!handshakeDone) {
                            Codecs.closeLater(channel, true, Protocol.CLOSE_EARLY_UNCONFIRMED);
                        }
                    }, confirmMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            } else {
                waitingV3.add(connection);
            }
        }

        /** The {@code /3} counterpart of {@link #deliver}: the v2 preamble, then the acceptor, then the gate. */
        private void deliverV3(V3ConnectionImpl connection) {
            byte[] next = null;
            try {
                rs.sudoe.quicraft.core.Faults.check(rs.sudoe.quicraft.core.Faults.EARLY_TOKEN_ISSUE);
                next = earlyData ? tokens.issue() : null;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "issuing an early token failed; none this time", t);
            }
            try {
                connection.writePreamble(next);
                acceptor.accept(connection);
                connection.openGate();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "QUIC stream acceptor failed; closing the connection", t);
                connection.close();
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof QuicConnectionCloseEvent) {
                QuicConnectionCloseEvent close = (QuicConnectionCloseEvent) evt;
                if (close.isApplicationClose() && close.error() == Protocol.CLOSE_EARLY_ABANDONED) {
                    unconfirmed.abandoned.incrementAndGet();
                }
            }
            if (evt instanceof SslHandshakeCompletionEvent) {
                SslHandshakeCompletionEvent done = (SslHandshakeCompletionEvent) evt;
                if (done.isSuccess()) {
                    handshakeDone = true;
                    unconfirmed.remove((QuicChannel) ctx.channel());
                    if (confirmDeadline != null) {
                        confirmDeadline.cancel(false);
                    }
                    for (ServerStream stream : waiting) {
                        deliver(stream);
                    }
                    waiting.clear();
                    for (V3ConnectionImpl connection : waitingV3) {
                        deliverV3(connection);
                    }
                    waitingV3.clear();
                } else {
                    ctx.close();
                }
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            unconfirmed.remove((QuicChannel) ctx.channel());
            ctx.fireChannelInactive();
        }

        /**
         * Closes the connection with an explicit error where quiche can still send one. Known
         * limit (Netty 4.2.19): on a TLS failure such as no common ALPN, the error escapes
         * quiche's send path and no CONNECTION_CLOSE goes out, so the client only learns of it at
         * its connect timeout. Racing covers that case: TCP starts after the head start
         * (docs/protocol.md §5).
         */
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                Codecs.closeLater((QuicChannel) ctx.channel(), false, CRYPTO_ERROR_HANDSHAKE_FAILURE);
            } else {
                LOG.log(Level.FINE, "QUIC connection error", cause);
                Codecs.closeLater((QuicChannel) ctx.channel(), true, Protocol.CLOSE_INTERNAL_ERROR);
            }
        }

        private void deliver(ServerStream stream) {
            byte[] next = null;
            try {
                rs.sudoe.quicraft.core.Faults.check(rs.sudoe.quicraft.core.Faults.EARLY_TOKEN_ISSUE);
                next = earlyData ? tokens.issue() : null;
            } catch (Throwable t) {
                LOG.log(Level.FINE, "issuing an early token failed; none this time", t);
            }
            try {
                stream.writePreamble(next);
                acceptor.accept(stream);
                stream.openGate();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "QUIC stream acceptor failed; closing the connection", t);
                stream.close();
            }
        }
    }

    /** Connections passed to the game on 0-RTT data whose handshake hasn't completed yet. */
    static final class Unconfirmed {
        static final class Entry {
            final QuicChannel connection;
            final InetSocketAddress address;
            /** From the Login Start in the early data; null until read. */
            volatile String name;

            Entry(QuicChannel connection, InetSocketAddress address) {
                this.connection = connection;
                this.address = address;
            }
        }

        private final java.util.Map<QuicChannel, Entry> entries = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicLong released = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong abandoned = new java.util.concurrent.atomic.AtomicLong();

        Entry add(QuicChannel connection, InetSocketAddress address) {
            released.incrementAndGet();
            Entry e = new Entry(connection, address);
            entries.put(connection, e);
            return e;
        }

        void remove(QuicChannel connection) {
            entries.remove(connection);
        }

        CompletableFuture<Integer> close(InetSocketAddress login, String name) {
            List<CompletableFuture<Void>> closing = new ArrayList<>();
            for (Entry e : entries.values()) {
                if (e.address.getAddress().equals(login.getAddress()) && !e.address.equals(login)
                        && name.equalsIgnoreCase(e.name)) {
                    entries.remove(e.connection);
                    CompletableFuture<Void> closed = new CompletableFuture<>();
                    e.connection.closeFuture().addListener(f -> closed.complete(null));
                    Codecs.closeLater(e.connection, true, Protocol.CLOSE_EARLY_UNCONFIRMED);
                    closing.add(closed);
                }
            }
            int count = closing.size();
            return CompletableFuture.allOf(closing.toArray(new CompletableFuture<?>[0])).thenApply(v -> count);
        }
    }
}
