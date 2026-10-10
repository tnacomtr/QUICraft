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
 */
public final class QuicServer implements Closeable {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    /** RFC 9001 §4.8: CRYPTO_ERROR 0x100 + TLS alert handshake_failure (40). */
    private static final int CRYPTO_ERROR_HANDSHAKE_FAILURE = 0x100 + 40;

    /** Receives each accepted stream on the stream's thread. Must set a listener, then enable auto-read. */
    public interface StreamAcceptor {
        void accept(QuicByteStream stream);
    }

    private final EventLoopGroup group;
    private final Channel channel;
    private final Unconfirmed unconfirmed;

    private QuicServer(EventLoopGroup group, Channel channel, Unconfirmed unconfirmed) {
        this.group = group;
        this.channel = channel;
        this.unconfirmed = unconfirmed;
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

    /** Binds {@code address} and runs QUIC on core's own thread. */
    public static QuicServer bind(InetSocketAddress address, ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor) throws Exception {
        Unconfirmed unconfirmed = new Unconfirmed();
        io.netty.channel.ChannelHandler codec = codec(identity, config, acceptor, unconfirmed);
        EventLoopGroup group = Codecs.newGroup("quicraft-server", 1);
        try {
            Channel channel = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec)
                    .bind(address).sync().channel();
            return new QuicServer(group, channel, unconfirmed);
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
        try {
            channel.pipeline().addLast(codec(identity, config, acceptor, unconfirmed));
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
        return new QuicServer(null, channel, unconfirmed);
    }

    /** Deadline for a connection passed early to complete its handshake. Tests shorten it. */
    static volatile long confirmMillis = 3_000;

    private static io.netty.channel.ChannelHandler codec(ServerIdentity identity, TransportConfig config,
            StreamAcceptor acceptor, Unconfirmed unconfirmed) throws Exception {
        EarlyTokens tokens = new EarlyTokens();
        QuicSslContext ssl = QuicSslContextBuilder
                .forServer(identity.privateKey(), null, identity.certificate())
                .applicationProtocols(Protocol.ALPN)
                .earlyData(config.earlyData)
                .build();
        return Codecs.apply(new QuicServerCodecBuilder(), config, true)
                .sslContext(ssl)
                // Address validation (Retry) under load comes with Phase 4 (docs/protocol.md §9).
                .tokenHandler(NoRetryTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel connection) {
                        connection.pipeline().addLast(new Connection(acceptor, tokens, config.earlyData, unconfirmed));
                    }
                })
                .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
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
