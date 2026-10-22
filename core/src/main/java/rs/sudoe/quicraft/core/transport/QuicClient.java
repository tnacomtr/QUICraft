// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
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
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import rs.sudoe.quicraft.core.Faults;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.multistream.JoinDiag;
import rs.sudoe.quicraft.core.multistream.MultistreamProtocolException;
import rs.sudoe.quicraft.core.multistream.PreambleV2;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager.FingerprintMismatchException;

/** Opens QUIC connections and their v1 stream (docs/protocol.md §5, §8). */
public final class QuicClient {
    private static ChannelInitializer<QuicStreamChannel> readGrantInitializer() {
        return new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel stream) {
                rs.sudoe.quicraft.core.multistream.ReadGrantAllocator.install(stream);
            }
        };
    }

    private static final Logger LOG = Logger.getLogger("QUICraft");
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** Bounds of the grace period for a late server preamble after 0-RTT (docs/protocol.md §8). */
    private static final long MIN_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final long MAX_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    /** Tests only: a higher floor for the grace period; 0 keeps the specified one. */
    private static volatile long testGraceFloorNanos;
    /** Tests only: pauses immediately before a successful connect future is completed. */
    private static volatile Runnable testBeforeConnectCompletion;
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
            Dial dial = new Dial(fingerprint, config, tls(fingerprint, config));
            String peerHost = remote.getAddress().getHostAddress();
            int peerPort = remote.getPort();
            ChannelHandler codec = Codecs.apply(new QuicClientCodecBuilder(), config, false)
                    // Host and port key the context's session cache.
                    .sslEngineProvider(q -> {
                        QuicSslEngine e = dial.tls.get().context.newEngine(q.alloc(), peerHost, peerPort);
                        dial.engine.set(e);
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
                        config.earlyData && config.sessionResumption ? early : null, result, dial);
                attempt.start();
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    /**
     * Dialing {@code /3} where the loaded native cannot support it (13.13). Distinct so a caller
     * can fall back to {@code /2} or TCP (12.3) without mistaking it for an ordinary connect
     * failure.
     */
    public static final class V3UnavailableException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        V3UnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Dials a {@code quicraft/3} connection (docs/protocol.md 13.1): the ALPN is
     * {@link Protocol#ALPN_V3}, the 13.13 transport parameters are in force, stream 0 begins with
     * a {@link rs.sudoe.quicraft.core.multistream.PreambleV2} preamble, and the result is a
     * {@link V3Connection}. {@code table} is this client's routing table for its protocol number
     * (13.7); it is never guessed.
     *
     * <p>0-RTT works exactly as for {@code /2} (user decision 2026-10-16, 12.18): with an
     * {@link EarlyFlight} whose join inputs were recorded on an earlier {@code /3} join, the
     * {@code /3}-partitioned early token and recorded flight go out as early data (12.17).
     *
     * <p>Fails fast with {@link V3UnavailableException} on a native that cannot cap quiche's
     * flow-control windows, so the caller can fall back (12.3, 13.13).
     */
    public static CompletableFuture<V3Connection> connectV3(InetSocketAddress remote, Fingerprint fingerprint,
            RoutingTable table, EarlyFlight early) {
        return connectV3(remote, fingerprint, table, early, false);
    }

    /**
     * As {@link #connectV3(InetSocketAddress, Fingerprint, RoutingTable, EarlyFlight)}, with
     * {@code bypassResumption} forcing a full handshake while leaving any stored ticket untouched
     * (12.16's changed-pin re-bind).
     */
    public static CompletableFuture<V3Connection> connectV3(InetSocketAddress remote, Fingerprint fingerprint,
            RoutingTable table, EarlyFlight early, boolean bypassResumption) {
        return connectV3(remote, fingerprint, table, TransportConfig.DEFAULT, early, bypassResumption);
    }

    /**
     * As {@link #connectV3(InetSocketAddress, Fingerprint, RoutingTable, EarlyFlight, boolean)}, with
     * {@code config} as this dial's base: its admission budgets, idle timeout, congestion control,
     * initial cwnd, {@code earlyData} and {@code sessionResumption} all apply, with the 13.13
     * multi-stream limits added ({@link TransportConfig#withMultistream()}). {@code earlyData} off
     * means no 0-RTT for this dial, whatever material is cached.
     */
    public static CompletableFuture<V3Connection> connectV3(InetSocketAddress remote, Fingerprint fingerprint,
            RoutingTable table, TransportConfig config, EarlyFlight early, boolean bypassResumption) {
        if (table == null) {
            throw new NullPointerException("table");
        }
        if (config == null) {
            throw new NullPointerException("config");
        }
        if (!QuicSupport.hasMaxFlowControlWindows()) {
            CompletableFuture<V3Connection> unavailable = new CompletableFuture<V3Connection>();
            unavailable.completeExceptionally(new V3UnavailableException(
                    "/3 needs QUICraft's patched native that caps quiche's flow-control windows (12.3, 13.13);"
                            + " fall back to /2 or TCP"));
            return unavailable;
        }
        TransportConfig effective = config.withMultistream();
        return connectV3(codec -> new Bootstrap().group(group()).channel(NioDatagramChannel.class).handler(codec)
                .bind(0), remote, fingerprint, table, effective, early, bypassResumption);
    }

    /**
     * As {@link #connectV3(InetSocketAddress, Fingerprint, RoutingTable, TransportConfig,
     * EarlyFlight, boolean)}, but QUIC runs on the platform's {@code loop} over its {@code socket}
     * (hosted transport, docs/protocol.md §11), exactly as the hosted {@code /2} dial does: the
     * connection's event loop is the game's, so the TLS exporter, the binding session's deadlines and
     * the game channel all run on the one thread 12.10 requires. The socket is closed with the
     * connection, or when the attempt fails or is cancelled.
     */
    public static CompletableFuture<V3Connection> connectV3(HostLoop loop, HostDatagramSocket socket,
            InetSocketAddress remote, Fingerprint fingerprint, RoutingTable table, TransportConfig config,
            EarlyFlight early, boolean bypassResumption) {
        // Every path below closes the socket through this one guard, so it is closed at most once
        // (the channel's close and the failed-attempt hook can both reach it).
        OnceClosingSocket guarded = socket == null ? null : new OnceClosingSocket(socket);
        try {
            if (table == null) {
                throw new NullPointerException("table");
            }
            if (config == null) {
                throw new NullPointerException("config");
            }
            if (socket == null) {
                throw new NullPointerException("socket");
            }
            if (!QuicSupport.hasMaxFlowControlWindows()) {
                closeHostedSocket(guarded);
                CompletableFuture<V3Connection> unavailable = new CompletableFuture<V3Connection>();
                unavailable.completeExceptionally(new V3UnavailableException(
                        "/3 needs QUICraft's patched native that caps quiche's flow-control windows (12.3, 13.13);"
                                + " fall back to /2 or TCP"));
                return unavailable;
            }
            CompletableFuture<V3Connection> result = connectV3(codec -> {
                HostedDatagramChannel udp = null;
                try {
                    udp = new HostedDatagramChannel(guarded);
                    udp.pipeline().addLast(codec);
                    return new HostedEventLoop(loop).register(udp);
                } catch (RuntimeException | Error setupFailure) {
                    if (udp != null) {
                        udp.close();
                    } else {
                        closeHostedSocket(guarded);
                    }
                    throw setupFailure;
                }
            }, remote, fingerprint, table, config.withMultistream(), early, bypassResumption);
            // The hosted socket becomes channel-owned on registration, but a failed/cancelled
            // attempt may fail before the channel factory ever takes ownership of it.
            result.whenComplete((connection, error) -> {
                if (error != null || result.isCancelled()) {
                    closeHostedSocket(guarded);
                }
            });
            return result;
        } catch (RuntimeException | Error setupFailure) {
            closeHostedSocket(guarded);
            throw setupFailure;
        }
    }

    /**
     * Delegates to the platform socket, but forwards {@link #close} only on the first call: the
     * platform's close is not documented as idempotent, and a hosted dial can reach close from both
     * the channel and the attempt's completion hook.
     */
    private static final class OnceClosingSocket implements HostDatagramSocket {
        private final HostDatagramSocket delegate;
        private final AtomicBoolean closed = new AtomicBoolean();

        OnceClosingSocket(HostDatagramSocket delegate) {
            this.delegate = delegate;
        }

        @Override
        public InetSocketAddress localAddress() {
            return delegate.localAddress();
        }

        @Override
        public void start(Receiver receiver) {
            delegate.start(receiver);
        }

        @Override
        public void write(java.nio.ByteBuffer data, InetSocketAddress recipient) {
            delegate.write(data, recipient);
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public boolean isWritable() {
            return delegate.isWritable();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }

    private static void closeHostedSocket(HostDatagramSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
                // Preserve the failed dial as the reported cause; close is best effort.
            }
        }
    }

    private static CompletableFuture<V3Connection> connectV3(UdpFactory udpFactory, InetSocketAddress remote,
            Fingerprint fingerprint, RoutingTable table, TransportConfig config, EarlyFlight early,
            boolean bypassResumption) {
        CompletableFuture<V3Connection> result = new CompletableFuture<V3Connection>();
        try {
            // bypassResumption: a context cached nowhere, so the stored tickets stay untouched and
            // the next ordinary dial still resumes (12.16).
            Tls tls = bypassResumption ? new Tls(fingerprint, config.earlyData, Protocol.ALPN_V3)
                    : tls(fingerprint, config, Protocol.ALPN_V3);
            V3Dial dial = new V3Dial(fingerprint, config, table, tls);
            String peerHost = remote.getAddress().getHostAddress();
            int peerPort = remote.getPort();
            ChannelHandler codec = Codecs.apply(new QuicClientCodecBuilder(), config, false)
                    .sslEngineProvider(q -> {
                        QuicSslEngine e = dial.tls.get().context.newEngine(q.alloc(), peerHost, peerPort);
                        dial.engine.set(e);
                        return e;
                    })
                    .build();
            udpFactory.open(codec).addListener((ChannelFuture opened) -> {
                if (!opened.isSuccess()) {
                    opened.channel().close();
                    result.completeExceptionally(opened.cause());
                    return;
                }
                V3Attempt attempt = new V3Attempt(opened.channel(), remote,
                        EarlyCache.server(remote, fingerprint, Protocol.ALPN_V3),
                        config.earlyData && config.sessionResumption ? early : null, result, dial);
                attempt.start();
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    /** A client TLS context and its trust manager, for one fingerprint and one ALPN. */
    private static final class Tls {
        final QuicSslContext context;
        final FingerprintTrustManager trust;

        Tls(Fingerprint fingerprint, boolean earlyData, String alpn) {
            trust = new FingerprintTrustManager(fingerprint);
            context = QuicSslContextBuilder.forClient()
                    .trustManager(trust)
                    // Trust is by fingerprint only; names mean nothing (docs/protocol.md §7).
                    .endpointIdentificationAlgorithm(null)
                    .applicationProtocols(alpn)
                    .earlyData(earlyData)
                    .build();
        }
    }

    /**
     * The TLS context the next handshake of one attempt uses, and the engine of the last one. A
     * retry after an abandoned 0-RTT connection swaps in a fresh context (see {@link #freshTls}).
     */
    private static final class Dial {
        final Fingerprint fingerprint;
        final TransportConfig config;
        final AtomicReference<Tls> tls;
        final AtomicReference<SSLEngine> engine = new AtomicReference<>();

        Dial(Fingerprint fingerprint, TransportConfig config, Tls tls) {
            this.fingerprint = fingerprint;
            this.config = config;
            this.tls = new AtomicReference<>(tls);
        }

        FingerprintMismatchException mismatch() {
            return tls.get().trust.mismatch(engine.get());
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
        return tls(fingerprint, config, Protocol.ALPN);
    }

    private static Tls tls(Fingerprint fingerprint, TransportConfig config, String alpn) {
        if (!config.sessionResumption) {
            return new Tls(fingerprint, config.earlyData, alpn);
        }
        List<Object> key = Arrays.<Object>asList(fingerprint, config.earlyData, alpn);
        synchronized (CONTEXTS) {
            Tls tls = CONTEXTS.get(key);
            if (tls == null) {
                tls = new Tls(fingerprint, config.earlyData, alpn);
                CONTEXTS.put(key, tls);
            }
            return tls;
        }
    }

    /**
     * A new context for {@code fingerprint} and {@code alpn}, replacing the cached one: it holds no
     * session ticket, so the next handshake is a full one. It is cached, so the ticket that
     * handshake returns is kept. Replacing drops the tickets of every server with this fingerprint
     * and ALPN, not only the one that was abandoned (the JDK-style session cache is not per host
     * here).
     */
    private static Tls freshTls(Fingerprint fingerprint, TransportConfig config) {
        return freshTls(fingerprint, config, Protocol.ALPN);
    }

    private static Tls freshTls(Fingerprint fingerprint, TransportConfig config, String alpn) {
        if (!config.sessionResumption) {
            return new Tls(fingerprint, config.earlyData, alpn);
        }
        List<Object> key = Arrays.<Object>asList(fingerprint, config.earlyData, alpn);
        Tls tls = new Tls(fingerprint, config.earlyData, alpn);
        synchronized (CONTEXTS) {
            CONTEXTS.put(key, tls);
        }
        return tls;
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
     * Whether a cached TLS context for {@code fingerprint} holds a session ticket from the server
     * at {@code remote}, so the next connect there can resume and offer 0-RTT data. Netty keeps
     * that cache package-private, so this reads it by reflection and answers false on any
     * failure; SessionResumptionTest checks that it still works. For tests and diagnostics.
     */
    public static boolean hasSessionTicket(InetSocketAddress remote, Fingerprint fingerprint) {
        List<Tls> candidates = new ArrayList<>();
        synchronized (CONTEXTS) {
            for (Map.Entry<List<Object>, Tls> e : CONTEXTS.entrySet()) {
                if (e.getKey().get(0).equals(fingerprint)) {
                    candidates.add(e.getValue());
                }
            }
        }
        for (Tls tls : candidates) {
            try {
                java.lang.reflect.Method get = tls.context.getClass().getDeclaredMethod("getSessionCache");
                get.setAccessible(true);
                Object cache = get.invoke(tls.context);
                if (cache == null) {
                    continue;
                }
                java.lang.reflect.Method has = cache.getClass().getDeclaredMethod("hasSession", String.class, int.class);
                has.setAccessible(true);
                if ((Boolean) has.invoke(cache, remote.getAddress().getHostAddress(), remote.getPort())) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.log(Level.FINE, "session cache not readable", e);
                return false;
            }
        }
        return false;
    }

    private static volatile String lastEarly = "no attempt";

    /**
     * Tests only: raises the floor of the grace period a client waits for the server's preamble
     * after 0-RTT (docs/protocol.md §8, "Rejected 0-RTT"; 5 ms by spec) to {@code millis}, or
     * restores the specified floor with 0. On loopback the handshake is so short that the spec's
     * floor applies, and a busy test machine can miss it, so the client rightly abandons 0-RTT
     * data a test expected to see used.
     */
    public static void setGraceFloorForTests(long millis) {
        testGraceFloorNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
    }

    static void setBeforeConnectCompletionForTests(Runnable hook) {
        testBeforeConnectCompletion = hook;
    }

    private static void beforeConnectCompletionForTests() {
        Runnable hook = testBeforeConnectCompletion;
        if (hook != null) {
            hook.run();
        }
    }

    /**
     * What the latest QUIC attempt did about 0-RTT: {@code sent}, or why it sent nothing (no
     * early-data event from TLS, no recorded flight, no early token, ...). For tests and
     * diagnostics; attempts running at once overwrite each other's answer.
     */
    public static String lastEarlyOutcome() {
        return lastEarly;
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
        private final Dial dial;
        private final AtomicReference<QuicChannel> quic = new AtomicReference<>();
        /** Set when 0-RTT data is on its way; completes with its stream, or null if that failed. */
        private volatile CompletableFuture<ClientStream> earlyStream;
        /** Set when the connection that sent 0-RTT data is abandoned (docs/protocol.md §8). */
        private volatile QuicChannel abandoned;
        /** Set for the one retry after an abandoned connection: a full handshake, no 0-RTT. */
        private volatile boolean retrying;
        /** Nanos at which the first connect started; the handshake time is measured from it. */
        private volatile long connectStart;

        Attempt(Channel udp, InetSocketAddress remote, List<Object> server, EarlyFlight early,
                CompletableFuture<QuicByteStream> result, Dial dial) {
            this.udp = udp;
            this.remote = remote;
            this.server = server;
            this.early = early;
            lastEarly = early == null ? "not requested" : "no early-data event";
            this.result = result;
            this.dial = dial;
        }

        void start() {
            result.whenComplete((stream, error) -> {
                if (result.isCancelled() || error != null) {
                    abandon();
                }
            });
            if (result.isDone()) {
                return; // cancelled while the socket opened
            }
            connectStart = System.nanoTime();
            connect();
        }

        private void connect() {
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
                    FingerprintMismatchException wrongCertificate = dial.mismatch();
                    if (wrongCertificate != null) {
                        wrongCertificate.addSuppressed(cause);
                        cause = wrongCertificate;
                    }
                    result.completeExceptionally(cause);
                    return;
                }
                long handshake = System.nanoTime() - connectStart;
                QuicChannel connection = (QuicChannel) f.getNow();
                // The abandoned connection shares the socket with the retry: it must not close it.
                connection.closeFuture().addListener(closed -> {
                    if (connection != abandoned) {
                        udp.close();
                    }
                });
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
                        } else {
                            awaitPreamble(connection, stream, handshake);
                        }
                    }));
                }
            });
        }

        /**
         * The handshake completed with 0-RTT data out. The connection goes to the game as soon as
         * the server's preamble is read. If it is still missing after a grace period, the 0-RTT
         * was rejected (or lost): the connection is abandoned and the attempt retried, without the
         * game seeing it (docs/protocol.md §8, "Rejected 0-RTT"). On the connection's event loop.
         */
        private void awaitPreamble(QuicChannel connection, ClientStream stream, long handshakeNanos) {
            if (stream.preambleRead() || !connection.isOpen()) {
                deliver(stream);
                return;
            }
            long grace = Math.max(Math.max(MIN_GRACE_NANOS, testGraceFloorNanos),
                    Math.min(MAX_GRACE_NANOS, handshakeNanos / 4));
            boolean[] settled = new boolean[1]; // event loop only
            ScheduledFuture<?> timer = connection.eventLoop().schedule(() -> {
                if (settled[0]) {
                    return;
                }
                settled[0] = true;
                if (stream.preambleRead() || !connection.isOpen() || result.isDone()) {
                    deliver(stream);
                } else {
                    abandonEarly(connection);
                }
            }, grace, TimeUnit.NANOSECONDS);
            stream.whenPreambleRead(() -> {
                if (settled[0]) {
                    return;
                }
                settled[0] = true;
                timer.cancel(false);
                // After the read that completed the preamble has finished with its data.
                connection.eventLoop().execute(() -> deliver(stream));
            });
        }

        private void deliver(ClientStream stream) {
            if (!result.complete(stream)) {
                stream.close(); // cancelled meanwhile
            }
        }

        /**
         * Closes the connection that sent 0-RTT data with {@code 0x4} and connects again over the
         * same socket: a full handshake with a context that holds no session for this server.
         * Its failure is the attempt's failure. Only one retry.
         */
        private void abandonEarly(QuicChannel connection) {
            try {
                abandoned = connection;
                retrying = true;
                lastEarly = "sent, then abandoned: no server preamble within the grace period";
                earlyStream = null;
                dial.tls.set(freshTls(dial.fingerprint, dial.config));
                Codecs.closeLater(connection, true, Protocol.CLOSE_EARLY_ABANDONED);
                connect();
            } catch (Throwable t) {
                // Fails the attempt as usual: the race falls back to TCP (docs/protocol.md §5).
                result.completeExceptionally(t);
            }
        }

        /**
         * The TLS session allows early data (a resumed session): sends the preamble with this
         * server's token and the recorded first flight as 0-RTT data, if both are there
         * (docs/protocol.md §8). Any failure leaves the attempt as it would be without 0-RTT.
         */
        private void sendEarly(QuicChannel connection) {
            if (early == null || retrying || result.isDone() || earlyStream != null) {
                lastEarly = early == null ? "not requested" : retrying ? "retrying" : result.isDone()
                        ? "attempt already done" : "already sent";
                return;
            }
            byte[] flight;
            byte[] token;
            try {
                Faults.check(Faults.EARLY_SEND);
                EarlyCache cache = EarlyCache.INSTANCE;
                flight = cache.flight(server, early.joinInputs());
                if (flight == null || !cache.hasToken(server)) {
                    lastEarly = flight == null ? "no recorded flight" : "no early token";
                    return;
                }
                token = cache.takeToken(server);
                if (token == null) {
                    lastEarly = "no early token";
                    return;
                }
            } catch (Throwable t) {
                lastEarly = "skipped: " + t;
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
                            lastEarly = "sent";
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
                        beforeConnectCompletionForTests();
                        if (!result.complete(stream)) {
                            stream.close(); // cancelled meanwhile
                        }
                    });
        }

        /**
         * The attempt failed or lost the race. Once a QUIC channel exists, close it explicitly so
         * the peer can release the connection instead of waiting for its idle timeout. Closing the
         * UDP socket alone only force-closes our local channel. Before the client has received any
         * server packet quiche cannot send an authenticated CONNECTION_CLOSE, so in that case the
         * server's handshake deadline remains the backstop.
         */
        private void abandon() {
            QuicChannel connection = quic.get();
            if (connection == null || !connection.isOpen()) {
                udp.close();
                return;
            }
            connection.eventLoop().execute(() -> connection
                    .close(true, Protocol.CLOSE_NORMAL, io.netty.buffer.Unpooled.EMPTY_BUFFER)
                    // This delay only applies if this listener runs first; closeFuture may close UDP sooner.
                    .addListener(closed -> udp.eventLoop().schedule(() -> udp.close(), 50,
                            java.util.concurrent.TimeUnit.MILLISECONDS)));
        }
    }

    /** One /3 attempt's TLS context and routing table, swapped on an abandoned-0-RTT retry. */
    private static final class V3Dial {
        final Fingerprint fingerprint;
        final TransportConfig config;
        final RoutingTable table;
        final AtomicReference<Tls> tls;
        final AtomicReference<SSLEngine> engine = new AtomicReference<SSLEngine>();

        V3Dial(Fingerprint fingerprint, TransportConfig config, RoutingTable table, Tls tls) {
            this.fingerprint = fingerprint;
            this.config = config;
            this.table = table;
            this.tls = new AtomicReference<Tls>(tls);
        }

        FingerprintMismatchException mismatch() {
            return tls.get().trust.mismatch(engine.get());
        }
    }

    /** One /3 connect attempt over its own UDP channel; the {@link Attempt} of {@code /2} in /3 terms. */
    private static final class V3Attempt {
        private final Channel udp;
        private final InetSocketAddress remote;
        private final List<Object> server;
        private final EarlyFlight early;
        private final CompletableFuture<V3Connection> result;
        private final V3Dial dial;
        private final AtomicReference<QuicChannel> quic = new AtomicReference<QuicChannel>();
        /** The stream the game will get, once it exists (set on the event loop). */
        private volatile V3ConnectionImpl v3;
        /** Set when 0-RTT data is on its way; completes with its connection, or null if that failed. */
        private volatile CompletableFuture<V3ConnectionImpl> earlyStream;
        /** Set when the connection that sent 0-RTT data is abandoned (docs/protocol.md §8). */
        private volatile QuicChannel abandoned;
        /** Set for the one retry after an abandoned connection: a full handshake, no 0-RTT. */
        private volatile boolean retrying;
        /** Nanos at which the first connect started; the handshake time is measured from it. */
        private volatile long connectStart;
        /** This attempt's join marks, or null when {@code -Dquicraft.v3.joindiag} is off. */
        private JoinDiag diag;

        V3Attempt(Channel udp, InetSocketAddress remote, List<Object> server, EarlyFlight early,
                CompletableFuture<V3Connection> result, V3Dial dial) {
            this.udp = udp;
            this.remote = remote;
            this.server = server;
            this.early = early;
            lastEarly = early == null ? "not requested" : "no early-data event";
            this.result = result;
            this.dial = dial;
        }

        void start() {
            result.whenComplete((stream, error) -> {
                if (result.isCancelled() || error != null) {
                    // No join follows a failed attempt, so its one line is written here.
                    JoinDiag.finish(diag);
                    abandon();
                }
            });
            if (result.isDone()) {
                return; // cancelled while the socket opened
            }
            diag = JoinDiag.create(JoinDiag.Side.CLIENT);
            JoinDiag.mark(diag, "connect start");
            connectStart = System.nanoTime();
            connect();
        }

        private void connect() {
            Future<QuicChannel> connecting = QuicChannel.newBootstrap(udp)
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
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel stream) {
                            if (stream.isLocalCreated()) {
                                return; // stream 0 is created with its own handler below
                            }
                            rs.sudoe.quicraft.core.multistream.ReadGrantAllocator.install(stream);
                            // /3 server-created streams are 1+4i: N CHUNK shards, then the ACK stream (13.3).
                            V3ConnectionImpl current = v3;
                            long id = stream.streamId();
                            if (current == null || !current.preambleRead()
                                    || stream.type() != QuicStreamType.BIDIRECTIONAL
                                    || id < 1 || (id - 1) % 4 != 0
                                    || (id - 1) / 4 >= current.sessionContext().shardCount() + 1) {
                                Codecs.closeLater(stream.parent(), true,
                                        MultistreamProtocolException.CLOSE_ORDERING);
                                return;
                            }
                            current.acceptPeerStream(stream);
                        }
                    })
                    .remoteAddress(remote)
                    .connect();
            connecting.addListener(f -> {
                if (!f.isSuccess()) {
                    Throwable cause = f.cause();
                    FingerprintMismatchException wrongCertificate = dial.mismatch();
                    if (wrongCertificate != null) {
                        wrongCertificate.addSuppressed(cause);
                        cause = wrongCertificate;
                    }
                    result.completeExceptionally(cause);
                    return;
                }
                long handshake = System.nanoTime() - connectStart;
                QuicChannel connection = (QuicChannel) f.getNow();
                JoinDiag.mark(diag, "QUIC handshake done");
                // The abandoned connection shares the socket with the retry: it must not close it.
                connection.closeFuture().addListener(closed -> {
                    if (connection != abandoned) {
                        udp.close();
                    }
                });
                if (result.isDone()) {
                    Codecs.closeLater(connection, true, Protocol.CLOSE_NORMAL);
                    return;
                }
                CompletableFuture<V3ConnectionImpl> sent = earlyStream;
                if (sent == null) {
                    openStream(connection);
                } else {
                    sent.whenComplete((stream, error) -> connection.eventLoop().execute(() -> {
                        if (stream == null) {
                            openStream(connection);
                        } else {
                            awaitPreamble(connection, stream, handshake);
                        }
                    }));
                }
            });
        }

        /**
         * The handshake completed with 0-RTT data out. The connection goes to the game as soon as
         * the server's preamble is read. If it is still missing after a grace period, the 0-RTT was
         * rejected (or lost): the connection is abandoned and the attempt retried, without the game
         * seeing it (docs/protocol.md §8, "Rejected 0-RTT"). On the connection's event loop.
         */
        private void awaitPreamble(QuicChannel connection, V3ConnectionImpl stream, long handshakeNanos) {
            if (stream.preambleRead() || !connection.isOpen()) {
                deliver(stream);
                return;
            }
            long grace = Math.max(Math.max(MIN_GRACE_NANOS, testGraceFloorNanos),
                    Math.min(MAX_GRACE_NANOS, handshakeNanos / 4));
            boolean[] settled = new boolean[1]; // event loop only
            ScheduledFuture<?> timer = connection.eventLoop().schedule(() -> {
                if (settled[0]) {
                    return;
                }
                settled[0] = true;
                if (stream.preambleRead() || !connection.isOpen() || result.isDone()) {
                    deliver(stream);
                } else {
                    abandonEarly(connection);
                }
            }, grace, TimeUnit.NANOSECONDS);
            stream.whenPreambleRead(() -> {
                if (settled[0]) {
                    return;
                }
                settled[0] = true;
                timer.cancel(false);
                // After the read that completed the preamble has finished with its data.
                connection.eventLoop().execute(() -> deliver(stream));
            });
        }

        private void deliver(V3ConnectionImpl stream) {
            if (!result.complete(stream)) {
                stream.close(); // cancelled meanwhile
            }
        }

        /**
         * Closes the connection that sent 0-RTT data with {@code 0x4} and connects again over the
         * same socket: a full handshake with a context that holds no session for this server. Its
         * failure is the attempt's failure. Only one retry.
         */
        private void abandonEarly(QuicChannel connection) {
            try {
                abandoned = connection;
                retrying = true;
                lastEarly = "sent, then abandoned: no server preamble within the grace period";
                earlyStream = null;
                // freshTls replaces the cached context for (fingerprint, /3), so the tickets stored
                // under this ALPN are dropped: a bypassResumption dial keeps its tickets only until
                // an abandoned 0-RTT retry replaces the shared context (12.16, 12.17).
                dial.tls.set(freshTls(dial.fingerprint, dial.config, Protocol.ALPN_V3));
                Codecs.closeLater(connection, true, Protocol.CLOSE_EARLY_ABANDONED);
                connect();
            } catch (Throwable t) {
                // Fails the attempt as usual: the race falls back to TCP (docs/protocol.md §5).
                result.completeExceptionally(t);
            }
        }

        /**
         * The TLS session allows early data (a resumed session): sends the v2 preamble with this
         * server's {@code /3} early token and the recorded first flight as 0-RTT data, if both are
         * there (docs/protocol.md §8, 12.17). Any failure leaves the attempt as it would be without
         * 0-RTT.
         */
        private void sendEarly(QuicChannel connection) {
            if (early == null || retrying || result.isDone() || earlyStream != null) {
                lastEarly = early == null ? "not requested" : retrying ? "retrying" : result.isDone()
                        ? "attempt already done" : "already sent";
                return;
            }
            byte[] flight;
            byte[] token;
            try {
                Faults.check(Faults.EARLY_SEND);
                EarlyCache cache = EarlyCache.INSTANCE;
                flight = cache.flight(server, early.joinInputs());
                if (flight == null || !cache.hasToken(server)) {
                    lastEarly = flight == null ? "no recorded flight" : "no early token";
                    return;
                }
                token = cache.takeToken(server);
                if (token == null) {
                    lastEarly = "no early token";
                    return;
                }
            } catch (Throwable t) {
                lastEarly = "skipped: " + t;
                LOG.log(Level.FINE, "0-RTT skipped", t);
                return;
            }
            CompletableFuture<V3ConnectionImpl> sent = new CompletableFuture<V3ConnectionImpl>();
            earlyStream = sent;
            // Netty fires this event inside its own send; a stream written from here would only go
            // out with the next flight, a round trip later. A task runs right after that send.
            connection.eventLoop().execute(() -> connection.createStream(QuicStreamType.BIDIRECTIONAL,
                    readGrantInitializer()).addListener(s -> {
                        if (!s.isSuccess()) {
                            LOG.log(Level.FINE, "0-RTT stream failed", s.cause());
                            sent.complete(null);
                            return;
                        }
                        try {
                            QuicStreamChannel channel = (QuicStreamChannel) s.getNow();
                            NettyQuicByteStream raw = new NettyQuicByteStream(channel);
                            byte[] preamble = PreambleV2.encode(token);
                            ByteBuffer out = ByteBuffer.allocate(preamble.length + flight.length);
                            out.put(preamble).put(flight).flip();
                            V3ConnectionImpl stream = V3ConnectionImpl.client(connection, channel, raw,
                                    EarlyCache.INSTANCE, server, early.joinInputs(), flight, dial.table, false);
                            v3 = stream;
                            stream.start();
                            raw.write(out);
                            raw.flush();
                            JoinDiag.mark(diag, "preamble+handshake flight written");
                            early.markSent();
                            lastEarly = "sent";
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
            connection.createStream(QuicStreamType.BIDIRECTIONAL, readGrantInitializer())
                    .addListener(s -> {
                        if (!s.isSuccess()) {
                            Codecs.closeLater(connection, true, Protocol.CLOSE_INTERNAL_ERROR);
                            result.completeExceptionally(s.cause());
                            return;
                        }
                        QuicStreamChannel channel = (QuicStreamChannel) s.getNow();
                        NettyQuicByteStream raw = new NettyQuicByteStream(channel);
                        // The connect future is done, so this connection's handshake is complete.
                        V3ConnectionImpl stream = V3ConnectionImpl.client(connection, channel, raw,
                                EarlyCache.INSTANCE, server, early == null ? null : early.joinInputs(), null,
                                dial.table, true);
                        v3 = stream;
                        stream.start();
                        raw.write(ByteBuffer.wrap(PreambleV2.encode(null)));
                        raw.flush();
                        JoinDiag.mark(diag, "preamble+handshake flight written");
                        beforeConnectCompletionForTests();
                        if (!result.complete(stream)) {
                            stream.close(); // cancelled meanwhile
                        }
                    });
        }

        /** Closes the peer-visible QUIC connection when possible; UDP close alone is only local. */
        private void abandon() {
            QuicChannel connection = quic.get();
            if (connection == null || !connection.isOpen()) {
                udp.close();
                return;
            }
            connection.eventLoop().execute(() -> connection
                    .close(true, Protocol.CLOSE_NORMAL, io.netty.buffer.Unpooled.EMPTY_BUFFER)
                    // This delay only applies if this listener runs first; closeFuture may close UDP sooner.
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
