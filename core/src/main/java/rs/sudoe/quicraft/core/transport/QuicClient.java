// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicSslEngine;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.SslEarlyDataReadyEvent;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager.FingerprintMismatchException;

/** Opens QUIC connections and their v1 stream (docs/protocol.md §5, §8). */
public final class QuicClient {
    private static final Logger LOG = Logger.getLogger("QUICraft");
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static volatile EventLoopGroup group;

    private QuicClient() {}

    /**
     * Connects, checks the server certificate against {@code fingerprint}, and opens the v1
     * stream. Cancelling the returned future closes the attempt, including a stream that
     * completes after the cancel. Runs on core's own QUIC thread.
     */
    public static CompletableFuture<QuicByteStream> connect(InetSocketAddress remote, Fingerprint fingerprint,
            TransportConfig config) {
        return connect(remote, fingerprint, config, null);
    }

    /**
     * As {@link #connect(InetSocketAddress, Fingerprint, TransportConfig)}, sending the first
     * flight recorded for {@code early}'s join inputs as 0-RTT data when it can (docs/protocol.md
     * §8). {@code early} null: no 0-RTT and nothing recorded.
     */
    public static CompletableFuture<QuicByteStream> connect(InetSocketAddress remote, Fingerprint fingerprint,
            TransportConfig config, EarlyFlight early) {
        return connect(codec -> new Bootstrap().group(group()).channel(NioDatagramChannel.class).handler(codec)
                .bind(0), remote, fingerprint, config, early);
    }

    /** From a given local address, e.g. to test a rejoin after a network change. */
    static CompletableFuture<QuicByteStream> connect(InetSocketAddress local, InetSocketAddress remote,
            Fingerprint fingerprint, TransportConfig config, EarlyFlight early) {
        return connect(codec -> new Bootstrap().group(group()).channel(NioDatagramChannel.class).handler(codec)
                .bind(local), remote, fingerprint, config, early);
    }

    /**
     * As {@link #connect(InetSocketAddress, Fingerprint, TransportConfig)}, but QUIC runs on the
     * platform's {@code loop} over its {@code socket} (hosted transport, docs/protocol.md §11):
     * no thread hop between the game pipeline and QUIC. The socket is closed with the
     * connection, or when the attempt fails or is cancelled.
     */
    public static CompletableFuture<QuicByteStream> connect(HostLoop loop, HostDatagramSocket socket,
            InetSocketAddress remote, Fingerprint fingerprint, TransportConfig config) {
        return connect(loop, socket, remote, fingerprint, config, null);
    }

    /** Hosted, with 0-RTT as in {@link #connect(InetSocketAddress, Fingerprint, TransportConfig, EarlyFlight)}. */
    public static CompletableFuture<QuicByteStream> connect(HostLoop loop, HostDatagramSocket socket,
            InetSocketAddress remote, Fingerprint fingerprint, TransportConfig config, EarlyFlight early) {
        return connect(codec -> {
            HostedDatagramChannel udp = new HostedDatagramChannel(socket);
            udp.pipeline().addLast(codec);
            return new HostedEventLoop(loop).register(udp);
        }, remote, fingerprint, config, early);
    }

    private interface UdpFactory {
        ChannelFuture open(ChannelHandler codec);
    }

    private static CompletableFuture<QuicByteStream> connect(UdpFactory udpFactory, InetSocketAddress remote,
            Fingerprint fingerprint, TransportConfig config, EarlyFlight early) {
        CompletableFuture<QuicByteStream> result = new CompletableFuture<>();
        try {
            Tls tls = tls(fingerprint, config);
            String peerHost = remote.getAddress().getHostAddress();
            int peerPort = remote.getPort();
            AtomicReference<SSLEngine> engine = new AtomicReference<>();
            ChannelHandler codec = Codecs.apply(new QuicClientCodecBuilder(), config, false)
                    // Host and port key the context's session cache.
                    .sslEngineProvider(q -> {
                        QuicSslEngine e = tls.context.newEngine(q.alloc(), peerHost, peerPort);
                        engine.set(e);
                        return e;
                    })
                    .build();
            udpFactory.open(codec).addListener((ChannelFuture opened) -> {
                if (!opened.isSuccess()) {
                    opened.channel().close();
                    result.completeExceptionally(opened.cause());
                    return;
                }
                Attempt attempt = new Attempt(opened.channel(), remote, EarlyCache.server(remote, fingerprint),
                        config.earlyData && config.sessionResumption ? early : null, result);
                attempt.start(() -> tls.trust.mismatch(engine.get()));
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    /** A client TLS context and its trust manager, for one fingerprint. */
    private static final class Tls {
        final QuicSslContext context;
        final FingerprintTrustManager trust;

        Tls(Fingerprint fingerprint, boolean earlyData) {
            trust = new FingerprintTrustManager(fingerprint);
            context = QuicSslContextBuilder.forClient()
                    .trustManager(trust)
                    // Trust is by fingerprint only; names mean nothing (docs/protocol.md §7).
                    .endpointIdentificationAlgorithm(null)
                    .applicationProtocols(Protocol.ALPN)
                    .earlyData(earlyData)
                    .build();
        }
    }

    private static final int MAX_CONTEXTS = 64;
    /**
     * TLS contexts by fingerprint and early-data setting. Each holds the session tickets of the
     * servers with that fingerprint (by IP and port), so a ticket is only offered to a server
     * advertising the fingerprint it was issued under (docs/protocol.md §8). In memory only.
     */
    private static final Map<List<Object>, Tls> CONTEXTS = new LinkedHashMap<List<Object>, Tls>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, Tls> eldest) {
            return size() > MAX_CONTEXTS;
        }
    };

    private static Tls tls(Fingerprint fingerprint, TransportConfig config) {
        if (!config.sessionResumption) {
            return new Tls(fingerprint, config.earlyData);
        }
        List<Object> key = Arrays.<Object>asList(fingerprint, config.earlyData);
        synchronized (CONTEXTS) {
            Tls tls = CONTEXTS.get(key);
            if (tls == null) {
                tls = new Tls(fingerprint, config.earlyData);
                CONTEXTS.put(key, tls);
            }
            return tls;
        }
    }

    /**
     * Drops every session ticket, early token and recorded first flight: the next connect to each
     * server is a full handshake without 0-RTT.
     */
    public static void forgetSessions() {
        synchronized (CONTEXTS) {
            CONTEXTS.clear();
        }
        EarlyCache.INSTANCE.clear();
    }

    /**
     * Certificate checks run so far for connections to servers with {@code fingerprint}; a
     * resumed session runs none. For tests and diagnostics.
     */
    public static int certificateChecks(Fingerprint fingerprint, TransportConfig config) {
        return tls(fingerprint, config).trust.checks();
    }

    /** One connect attempt over its own UDP channel. */
    private static final class Attempt {
        private final Channel udp;
        private final InetSocketAddress remote;
        private final List<Object> server;
        private final EarlyFlight early;
        private final CompletableFuture<QuicByteStream> result;
        private final AtomicReference<QuicChannel> quic = new AtomicReference<>();
        /** Set when 0-RTT data is on its way; completes with its stream, or null if that failed. */
        private volatile CompletableFuture<ClientStream> earlyStream;

        Attempt(Channel udp, InetSocketAddress remote, List<Object> server, EarlyFlight early,
                CompletableFuture<QuicByteStream> result) {
            this.udp = udp;
            this.remote = remote;
            this.server = server;
            this.early = early;
            this.result = result;
        }

        void start(Supplier<FingerprintMismatchException> mismatch) {
            result.whenComplete((stream, error) -> {
                if (result.isCancelled() || error != null) {
                    abandon();
                }
            });
            if (result.isDone()) {
                return; // cancelled while the socket opened
            }
            Future<QuicChannel> connecting = QuicChannel.newBootstrap(udp)
                    // Upper bound for a silent server; racing normally decides much sooner.
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .handler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void handlerAdded(ChannelHandlerContext ctx) {
                            quic.set((QuicChannel) ctx.channel());
                        }

                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt instanceof SslEarlyDataReadyEvent) {
                                sendEarly((QuicChannel) ctx.channel());
                            }
                            ctx.fireUserEventTriggered(evt);
                        }
                    })
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(remote)
                    .connect();
            connecting.addListener(f -> {
                if (!f.isSuccess()) {
                    Throwable cause = f.cause();
                    FingerprintMismatchException wrongCertificate = mismatch.get();
                    if (wrongCertificate != null) {
                        wrongCertificate.addSuppressed(cause);
                        cause = wrongCertificate;
                    }
                    result.completeExceptionally(cause);
                    return;
                }
                QuicChannel connection = (QuicChannel) f.getNow();
                connection.closeFuture().addListener(closed -> udp.close());
                if (result.isDone()) {
                    Codecs.closeLater(connection, true, Protocol.CLOSE_NORMAL);
                    return;
                }
                CompletableFuture<ClientStream> sent = earlyStream;
                if (sent == null) {
                    openStream(connection);
                } else {
                    sent.whenComplete((stream, error) -> connection.eventLoop().execute(() -> {
                        if (stream == null) {
                            openStream(connection);
                        } else if (!result.complete(stream)) {
                            stream.close(); // cancelled meanwhile
                        }
                    }));
                }
            });
        }

        /**
         * The TLS session allows early data (a resumed session): sends the preamble with this
         * server's token and the recorded first flight as 0-RTT data, if both are there
         * (docs/protocol.md §8). Any failure leaves the attempt as it would be without 0-RTT.
         */
        private void sendEarly(QuicChannel connection) {
            if (early == null || result.isDone() || earlyStream != null) {
                return;
            }
            byte[] flight;
            byte[] token;
            try {
                Faults.check(Faults.EARLY_SEND);
                EarlyCache cache = EarlyCache.INSTANCE;
                flight = cache.flight(server, early.joinInputs());
                if (flight == null || !cache.hasToken(server)) {
                    return;
                }
                token = cache.takeToken(server);
                if (token == null) {
                    return;
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "0-RTT skipped", t);
                return;
            }
            CompletableFuture<ClientStream> sent = new CompletableFuture<>();
            earlyStream = sent;
            // Netty fires this event inside its own send; a stream written from here would only go
            // out with the next flight, a round trip later. A task runs right after that send.
            connection.eventLoop().execute(() -> connection.createStream(QuicStreamType.BIDIRECTIONAL,
                    new ChannelInboundHandlerAdapter()).addListener(s -> {
                        if (!s.isSuccess()) {
                            LOG.log(Level.FINE, "0-RTT stream failed", s.cause());
                            sent.complete(null);
                            return;
                        }
                        try {
                            NettyQuicByteStream raw = new NettyQuicByteStream((QuicStreamChannel) s.getNow());
                            byte[] preamble = Preamble.encode(token);
                            ByteBuffer out = ByteBuffer.allocate(preamble.length + flight.length);
                            out.put(preamble).put(flight).flip();
                            ClientStream stream = new ClientStream(raw, EarlyCache.INSTANCE, server,
                                    early.joinInputs(), flight);
                            stream.start();
                            raw.write(out);
                            raw.flush();
                            early.markSent();
                            sent.complete(stream);
                            try {
                                Faults.check(Faults.EARLY_ABANDON);
                            } catch (Throwable t) {
                                result.completeExceptionally(t);
                            }
                        } catch (Throwable t) {
                            LOG.log(Level.FINE, "0-RTT send failed", t);
                            Codecs.closeLater(connection, true, Protocol.CLOSE_INTERNAL_ERROR);
                            sent.complete(null);
                        }
                    }));
        }

        private void openStream(QuicChannel connection) {
            connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .addListener(s -> {
                        if (!s.isSuccess()) {
                            Codecs.closeLater(connection, true, Protocol.CLOSE_INTERNAL_ERROR);
                            result.completeExceptionally(s.cause());
                            return;
                        }
                        NettyQuicByteStream raw = new NettyQuicByteStream((QuicStreamChannel) s.getNow());
                        ClientStream stream = new ClientStream(raw, EarlyCache.INSTANCE, server,
                                early == null ? null : early.joinInputs(), null);
                        stream.start();
                        raw.write(ByteBuffer.wrap(Preamble.encode(null)));
                        raw.flush();
                        if (!result.complete(stream)) {
                            stream.close(); // cancelled meanwhile
                        }
                    });
        }

        /**
         * The attempt failed or lost the race. Usually closing the socket is enough: that
         * force-closes the QUIC channel and fails the connect. (Cancelling Netty's connect future
         * instead makes QuicChannelBootstrap log an IllegalStateException when it later fails that
         * future.) After 0-RTT data the server may have started a login on it, so it gets a
         * CONNECTION_CLOSE first (docs/protocol.md §8, "TCP wins after 0-RTT went out"). quiche
         * sends one only if the client has received a packet from the server; otherwise the
         * server's confirmation deadline ends that login.
         */
        private void abandon() {
            QuicChannel connection = quic.get();
            if (connection == null || early == null || !early.sent() || !connection.isOpen()) {
                udp.close();
                return;
            }
            connection.eventLoop().execute(() -> connection
                    .close(true, Protocol.CLOSE_NORMAL, io.netty.buffer.Unpooled.EMPTY_BUFFER)
                    // Let the close datagram leave before the socket goes.
                    .addListener(closed -> udp.eventLoop().schedule(() -> udp.close(), 50,
                            java.util.concurrent.TimeUnit.MILLISECONDS)));
        }
    }

    private static EventLoopGroup group() {
        EventLoopGroup g = group;
        if (g == null) {
            synchronized (QuicClient.class) {
                g = group;
                if (g == null) {
                    g = Codecs.newGroup("quicraft-client", 1);
                    group = g;
                }
            }
        }
        return g;
    }
}
