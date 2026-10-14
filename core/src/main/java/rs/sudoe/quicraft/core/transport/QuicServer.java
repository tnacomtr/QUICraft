// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
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
import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.Protocol;
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
 * wrong socket still reaches the connection's codec.
 */
public final class QuicServer implements Closeable {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    /** RFC 9000 §20.1: transport error CONNECTION_REFUSED. */
    private static final int CONNECTION_REFUSED = 0x2;
    /** Marks a connection refused by the open-connection limits; its streams are ignored. */
    private static final io.netty.util.AttributeKey<Boolean> REFUSED =
            io.netty.util.AttributeKey.valueOf(QuicServer.class, "refused");
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

    /** Connections holding an open-connection lease whose handshake hasn't completed, for tests. */
    int pendingHandshakes() {
        return shared.admission.pendingLeases();
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
     * socket's event loop, for tests.
     */
    int[] acceptedConnectionsPerSocket() {
        int[] accepted = shared.accepted;
        return accepted == null ? new int[0] : accepted.clone();
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
        return bind(address, 1, identity, config, acceptor);
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
     */
    public static QuicServer bind(InetSocketAddress address, int socketCount, ServerIdentity identity,
            TransportConfig config, StreamAcceptor acceptor) throws Exception {
        int count = socketCount;
        if (count > MAX_UDP_SOCKETS) {
            if (SOCKET_COUNT_WARNING.compareAndSet(false, true)) {
                LOG.warning("A QUIC listener binds at most " + MAX_UDP_SOCKETS + " UDP sockets; using "
                        + MAX_UDP_SOCKETS + " of the requested " + socketCount);
            }
            count = MAX_UDP_SOCKETS;
        }
        if (count <= 1) {
            Shared shared = new Shared(1, identity, config, acceptor);
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
            return bind(address, identity, config, acceptor);
        }
        Shared shared = new Shared(count, identity, config, acceptor);
        SocketDispatcher dispatcher = new SocketDispatcher(shared);
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
                identity, config, acceptor);
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
        if (sockets.isEmpty() || loops.size() != sockets.size()) {
            throw new IllegalArgumentException("one loop per socket and at least one socket are required, got "
                    + loops.size() + " loops and " + sockets.size() + " sockets");
        }
        Shared shared = new Shared(sockets.size(), identity, config, acceptor);
        SocketDispatcher dispatcher = sockets.size() > 1 ? new SocketDispatcher(shared) : null;
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

    /** Deadline for a connection passed early to complete its handshake. Tests shorten it. */
    static volatile long confirmMillis = 10_000;

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
        final Unconfirmed unconfirmed = new Unconfirmed();
        final java.util.concurrent.atomic.AtomicLong retries = new java.util.concurrent.atomic.AtomicLong();
        /** Token-less attempts and open-connection leases; synchronized, safe from every loop. */
        final ConnectionAdmission admission;
        /** The ingress guard's datagram budget, shared by every socket's guard. */
        final ConnectionAdmission ingress;
        /** One instance for every socket's codec: it holds the listener's Retry key. */
        final AdmissionTokenHandler tokenHandler;
        final EarlyTokens tokens = new EarlyTokens();
        final QuicSslContext ssl;
        /** Connections accepted per socket, in bind order; null with a single socket. */
        final int[] accepted;

        Shared(int sockets, ServerIdentity identity, TransportConfig config, StreamAcceptor acceptor)
                throws Exception {
            this.config = config;
            this.acceptor = acceptor;
            this.accepted = sockets > 1 ? new int[sockets] : null;
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
            this.tokenHandler = new AdmissionTokenHandler(admission, validated, config.retryMode, retries);
            this.ssl = QuicSslContextBuilder
                    .forServer(identity.privateKey(), null, identity.certificate())
                    .applicationProtocols(Protocol.ALPN)
                    .earlyData(config.earlyData)
                    .build();
        }
    }

    /**
     * One dispatcher per listener, shared (Netty's {@link QuicCodecDispatcher} is {@code @Sharable})
     * by every socket's pipeline: it builds each socket's codec with the connection id generator
     * that encodes that socket's index into every id the codec issues, and forwards a datagram
     * whose destination connection id names another socket to that socket's codec.
     */
    private static final class SocketDispatcher extends QuicCodecDispatcher {
        private final Shared shared;
        /** Sockets added so far, in the same order as the dispatcher's own context list. */
        private final java.util.concurrent.atomic.AtomicInteger next =
                new java.util.concurrent.atomic.AtomicInteger();

        SocketDispatcher(Shared shared) {
            super(Codecs.LOCAL_CONNECTION_ID_LENGTH);
            this.shared = shared;
        }

        @Override
        protected void initChannel(Channel channel, int localConnectionIdLength, QuicConnectionIdGenerator idGenerator)
                throws Exception {
            int socketIndex = next.getAndIncrement();
            channel.pipeline().addLast(codec(shared, localConnectionIdLength, idGenerator, socketIndex));
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
                        Admitted admitted = Admitted.admit(connection, shared.admission);
                        if (admitted == null) {
                            return;
                        }
                        if (shared.accepted != null) {
                            // Written only by this socket's own loop, so no synchronization.
                            shared.accepted[socketIndex]++;
                        }
                        connection.pipeline().addLast(admitted,
                                new Connection(shared.acceptor, shared.tokens, shared.config.earlyData,
                                        shared.unconfirmed));
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        if (stream.parent().hasAttr(REFUSED)) {
                            return; // closing with CONNECTION_REFUSED already
                        }
                        Connection connection = stream.parent().pipeline().get(Connection.class);
                        if (connection == null || stream.type() != QuicStreamType.BIDIRECTIONAL
                                || stream.isLocalCreated()) {
                            Codecs.closeLater(stream.parent(), true, Protocol.CLOSE_PROTOCOL_VIOLATION);
                            return;
                        }
                        connection.offer(new NettyQuicByteStream(stream));
                    }
                });
        if (idGenerator != null) {
            builder.connectionIdAddressGenerator(idGenerator);
        }
        return builder.build();
    }

    /**
     * Per connection, first in its pipeline: holds its open-connection lease, confirms it when the
     * handshake completes and releases it on close, and closes a connection whose handshake hasn't
     * completed within {@link #handshakeMillis}.
     */
    private static final class Admitted extends ChannelInboundHandlerAdapter {
        /** Null when admission failed open. */
        private final ConnectionAdmission.Lease lease;
        private boolean handshakeDone;
        private java.util.concurrent.ScheduledFuture<?> deadline;

        private Admitted(ConnectionAdmission.Lease lease) {
            this.lease = lease;
        }

        /**
         * Takes an open-connection lease, released when the connection closes. Over a limit (not
         * expected: the token handler checked first), closes it with CONNECTION_REFUSED and
         * returns null. Runs inside the codec's read (the channel is registered there), where the
         * remote address is already set; fails open.
         */
        static Admitted admit(QuicChannel connection, ConnectionAdmission admission) {
            ConnectionAdmission.Lease lease = null;
            try {
                java.net.SocketAddress remote = connection.remoteSocketAddress();
                if (remote instanceof InetSocketAddress && ((InetSocketAddress) remote).getAddress() != null) {
                    lease = admission.tryAcquire(((InetSocketAddress) remote).getAddress());
                    if (lease == null) {
                        // Last resort: AdmissionTokenHandler checked the same limits on this thread
                        // just before the connection was created, so this should not happen. It
                        // can't fall back cleanly: Netty sends the queued handshake flight before the
                        // close (QuicheQuicChannel.java:615-616 in Netty 4.2.19, "send all that is
                        // queued before we close"), so the client completes its handshake and then
                        // sees the close, which reaches the game.
                        LOG.log(Level.FINE, "QUIC connection over the open-connection limits after admission; "
                                + "refusing it");
                        connection.attr(REFUSED).set(Boolean.TRUE);
                        Codecs.closeLater(connection, false, CONNECTION_REFUSED);
                        return null;
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "QUIC open-connection check failed; admitting the connection", t);
            }
            Admitted admitted = new Admitted(lease);
            // The Lease keeps its own key; the channel's address is cleared when it is freed.
            connection.closeFuture().addListener(f -> admitted.closed());
            return admitted;
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

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()
                    && !handshakeDone) {
                handshakeDone = true;
                if (deadline != null) {
                    deadline.cancel(false);
                }
                if (lease != null) {
                    lease.confirm();
                }
            }
            ctx.fireUserEventTriggered(evt);
        }

        private void closed() {
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (lease != null) {
                lease.close();
            }
        }
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
        private final List<ServerStream> waiting = new ArrayList<>(1);
        private ChannelHandlerContext ctx;
        private boolean handshakeDone;
        private java.util.concurrent.ScheduledFuture<?> confirmDeadline;

        Connection(StreamAcceptor acceptor, EarlyTokens tokens, boolean earlyData, Unconfirmed unconfirmed) {
            this.acceptor = acceptor;
            this.tokens = tokens;
            this.earlyData = earlyData;
            this.unconfirmed = unconfirmed;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        /** Called on the connection's event loop. The stream reads the client's preamble first. */
        void offer(NettyQuicByteStream stream) {
            new ServerStream(stream, this::onPreamble).start();
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
