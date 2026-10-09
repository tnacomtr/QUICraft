// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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
     * completes after the cancel.
     */
    public static CompletableFuture<QuicByteStream> connect(InetSocketAddress remote, Fingerprint fingerprint,
            TransportConfig config) {
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
            io.netty.channel.ChannelHandler codec = Codecs.apply(new QuicClientCodecBuilder(), config, false)
                    .sslContext(ssl)
                    .build();
            Channel udp = new Bootstrap().group(group()).channel(NioDatagramChannel.class).handler(codec)
                    .bind(0).syncUninterruptibly().channel();
            result.whenComplete((stream, error) -> {
                if (result.isCancelled() || error != null) {
                    udp.close();
                }
            });
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
                    connection.close(true, Protocol.CLOSE_NORMAL, Unpooled.EMPTY_BUFFER);
                    return;
                }
                connection.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                        .addListener(s -> {
                            if (!s.isSuccess()) {
                                connection.close(true, Protocol.CLOSE_INTERNAL_ERROR, Unpooled.EMPTY_BUFFER);
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
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
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
