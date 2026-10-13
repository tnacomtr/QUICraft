// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
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

    /** Receives each accepted stream on the stream's thread. Must set a listener, then enable auto-read. */
    public interface StreamAcceptor {
        void accept(QuicByteStream stream);
    }

    private final EventLoopGroup group;
    private final Channel channel;
    private final Unconfirmed unconfirmed;
    private final java.util.concurrent.atomic.AtomicLong retries;

    private QuicServer(EventLoopGroup group, Channel channel, Unconfirmed unconfirmed,
            java.util.concurrent.atomic.AtomicLong retries) {
        this.group = group;
        this.channel = channel;
        this.unconfirmed = unconfirmed;
        this.retries = retries;
    }

    /** Retry packets sent so far, for diagnostics and tests. */
    long retriesSent() {
        return retries.get();
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
        return unconfirmed.close(login, name);
    }

    /** Streams passed to the game on 0-RTT data so far, for diagnostics and tests. */
    public long earlyReleases() {
        return unconfirmed.released.get();
    }

    /** Connections the client closed with {@code 0x4} (early data abandoned) so far, for diagnostics and tests. */
    public long earlyAbandoned() {
        return unconfirmed.abandoned.get();
    }

    /** Binds {@code address} and runs QUIC on core's own thread. */
    public static QuicServer bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor) throws Exception {
        Unconfirmed unconfirmed = new Unconfirmed();
        java.util.concurrent.atomic.AtomicLong retries = new java.util.concurrent.atomic.AtomicLong();
        io.netty.channel.ChannelHandler codec = codec(identity, config, acceptor, unconfirmed, retries);
        IngressGuard guard = IngressGuard.forConfig(config);
        EventLoopGroup group = Codecs.newGroup("quicraft-server", 1);
        try {
            Channel channel = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(guard, codec);
                        }
                    })
                    .bind(address).sync().channel();
            return new QuicServer(group, channel, unconfirmed, retries);
        } catch (Exception | Error e) {
            group.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        HostedDatagramChannel channel = new HostedDatagramChannel(socket);
        Unconfirmed unconfirmed = new Unconfirmed();
        java.util.concurrent.atomic.AtomicLong retries = new java.util.concurrent.atomic.AtomicLong();
        try {
            channel.pipeline().addLast(IngressGuard.forConfig(config),
                    codec(identity, config, acceptor, unconfirmed, retries));
        } catch (Exception | Error e) {
            socket.close();
            throw e;
        }
        io.netty.channel.ChannelFuture registered = new HostedEventLoop(loop).register(channel);
        if (!loop.inEventLoop(Thread.currentThread())) {
            registered.sync();
        } else if (registered.isDone() && !registered.isSuccess()) {
            throw new IllegalStateException("registering the QUIC listener failed", registered.cause());
        }
        return new QuicServer(null, channel, unconfirmed, retries);
    }

    /** Deadline for a connection passed early to complete its handshake. Tests shorten it. */
    static volatile long confirmMillis = 10_000;

    /**
     * Deadline for every connection to complete its handshake, so spoofed or abandoned handshakes
     * can't hold open-connection slots. Separate from {@link #confirmMillis}. Tests shorten it.
     */
    static volatile long handshakeMillis = 10_000;

    private static io.netty.channel.ChannelHandler codec(ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor, Unconfirmed unconfirmed, java.util.concurrent.atomic.AtomicLong retries)
            throws Exception {
        EarlyTokens tokens = new EarlyTokens();
        // One per bind, like the Retry key inside the token handler.
        ConnectionAdmission admission = new ConnectionAdmission(
                config.globalAttemptsPerSecond, config.perIpAttemptsPerSecond, config.maxOpen,
                config.maxOpenPerIp, config.retryThresholdPerSecond, config.maxTrackedIps);
        // Attempts with a valid token get their own budget; its open caps are unused.
        ConnectionAdmission validated = new ConnectionAdmission(
                config.globalAttemptsPerSecond, config.perIpAttemptsPerSecond, 1, 1,
                config.retryThresholdPerSecond, config.maxTrackedIps);
        if ((long) config.maxTrackedIps < (long) config.maxOpen + config.globalAttemptsPerSecond) {
            LOG.warning("QUIC admission: maxTrackedIps (" + config.maxTrackedIps + ") is below maxOpen + "
                    + "globalAttemptsPerSecond (" + config.maxOpen + " + " + config.globalAttemptsPerSecond
                    + "); new addresses may be refused while the tracked ones fill it");
        }
        QuicSslContext ssl = QuicSslContextBuilder
                .forServer(identity.privateKey(), null, identity.certificate())
                .applicationProtocols(Protocol.ALPN)
                .earlyData(config.earlyData)
                .build();
        return Codecs.apply(new QuicServerCodecBuilder(), config, true)
                .sslContext(ssl)
                // Attempt limits and address validation (Retry), docs/protocol.md §9.
                .tokenHandler(new AdmissionTokenHandler(admission, validated, config.retryMode, retries))
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel connection) {
                        Admitted admitted = Admitted.admit(connection, admission);
                        if (admitted == null) {
                            return;
                        }
                        connection.pipeline().addLast(admitted,
                                new Connection(acceptor, tokens, config.earlyData, unconfirmed));
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
                })
                .build();
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
            deadline = ctx.executor().schedule(() -> {
                if (!handshakeDone) {
                    // Transport NO_ERROR: a real but slow client sees a failed handshake and falls
                    // back to TCP.
                    Codecs.closeLater(connection, false, 0x0);
                }
            }, handshakeMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
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
        return (InetSocketAddress) channel.localAddress();
    }

    @Override
    public void close() {
        if (group != null) {
            channel.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
        } else {
            // Hosted: the loop is the platform's. Bounded wait, in case it is already gone.
            channel.close().awaitUninterruptibly(2, java.util.concurrent.TimeUnit.SECONDS);
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
