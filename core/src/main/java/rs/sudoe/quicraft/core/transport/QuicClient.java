// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager;

/** Opens QUIC connections and their v1 stream (docs/protocol.md §5, §8). */
public final class QuicClient {
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
        return connect(codec -> new Bootstrap().group(group()).channel(NioDatagramChannel.class).handler(codec)
                .bind(0), remote, fingerprint, config);
    }

    /**
     * As {@link #connect(InetSocketAddress, Fingerprint, TransportConfig)}, but QUIC runs on the
     * platform's {@code loop} over its {@code socket} (hosted transport, docs/protocol.md §11):
     * no thread hop between the game pipeline and QUIC. The socket is closed with the
     * connection, or when the attempt fails or is cancelled.
     */
    public static CompletableFuture<QuicByteStream> connect(HostLoop loop, HostDatagramSocket socket,
            InetSocketAddress remote, Fingerprint fingerprint, TransportConfig config) {
        return connect(codec -> {
            HostedDatagramChannel udp = new HostedDatagramChannel(socket);
            udp.pipeline().addLast(codec);
            return new HostedEventLoop(loop).register(udp);
        }, remote, fingerprint, config);
    }

    private interface UdpFactory {
        ChannelFuture open(ChannelHandler codec);
    }

    private static CompletableFuture<QuicByteStream> connect(UdpFactory udpFactory, InetSocketAddress remote,
            Fingerprint fingerprint, TransportConfig config) {
        CompletableFuture<QuicByteStream> result = new CompletableFuture<>();
        try {
            FingerprintTrustManager trust = new FingerprintTrustManager(fingerprint);
            QuicSslContext ssl = QuicSslContextBuilder.forClient()
                    .trustManager(trust)
                    // Trust is by fingerprint only; names mean nothing (docs/protocol.md §7).
                    .endpointIdentificationAlgorithm(null)
                    .applicationProtocols(Protocol.ALPN)
                    .earlyData(config.earlyData)
                    .build();
            ChannelHandler codec = Codecs.apply(new QuicClientCodecBuilder(), config, false)
                    .sslContext(ssl)
                    .build();
            udpFactory.open(codec).addListener((ChannelFuture opened) -> {
                if (!opened.isSuccess()) {
                    opened.channel().close();
                    result.completeExceptionally(opened.cause());
                    return;
                }
                connect(opened.channel(), trust, remote, result);
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    private static void connect(Channel udp, FingerprintTrustManager trust, InetSocketAddress remote,
            CompletableFuture<QuicByteStream> result) {
        result.whenComplete((stream, error) -> {
            if (result.isCancelled() || error != null) {
                udp.close();
            }
        });
        if (result.isDone()) {
            return; // cancelled while the socket opened
        }
        Future<QuicChannel> connecting = QuicChannel.newBootstrap(udp)
                // Upper bound for a silent server; racing normally decides much sooner.
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInboundHandlerAdapter())
                .streamHandler(new ChannelInboundHandlerAdapter())
                .remoteAddress(remote)
                .connect();
        connecting.addListener(f -> {
            if (!f.isSuccess()) {
                Throwable cause = f.cause();
                if (trust.mismatch() != null) {
                    trust.mismatch().addSuppressed(cause);
                    cause = trust.mismatch();
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
            connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                    .addListener(s -> {
                        if (!s.isSuccess()) {
                            Codecs.closeLater(connection, true, Protocol.CLOSE_INTERNAL_ERROR);
                            result.completeExceptionally(s.cause());
                            return;
                        }
                        NettyQuicByteStream stream = new NettyQuicByteStream((QuicStreamChannel) s.getNow());
                        if (!result.complete(stream)) {
                            stream.close(); // cancelled meanwhile
                        }
                    });
        });
        result.whenComplete((stream, error) -> {
            if (result.isCancelled()) {
                connecting.cancel(false);
            }
        });
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
