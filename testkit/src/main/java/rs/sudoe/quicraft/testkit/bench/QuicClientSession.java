// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.bench;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import org.geysermc.mcprotocollib.network.helper.TransportHelper;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * MCProtocolLib's client session over QUIC: its Bootstrap gets a {@link QuicBridgeChannel}
 * instead of a TCP socket, and everything else (codec, encryption, compression, game protocol)
 * runs unchanged, as it would in the mod. QUIC is hosted on the session's own event loop, over
 * a UDP socket of MCProtocolLib's transport (epoll on Linux), as the mod does on the game's.
 */
final class QuicClientSession extends ClientNetworkSession {
    private EventLoop loop;
    private QuicByteStream stream;

    QuicClientSession(InetSocketAddress address, MinecraftProtocol protocol) {
        super(address, protocol, Runnable::run, null, null);
    }

    /** Opens the QUIC connection; call {@link #connect(boolean)} once it completes. */
    CompletableFuture<QuicByteStream> openQuic(InetSocketAddress target, Fingerprint fingerprint,
            TransportConfig config) {
        return openQuic(target, fingerprint, config, null, false);
    }

    /**
     * With 0-RTT ({@code early}, null for none), and optionally dropping every datagram from the
     * server at this socket, as if server-to-client UDP were blocked.
     */
    CompletableFuture<QuicByteStream> openQuic(InetSocketAddress target, Fingerprint fingerprint,
            TransportConfig config, EarlyFlight early, boolean dropFromServer) {
        loop = super.getEventLoopGroup().next();
        ChannelFactory<? extends DatagramChannel> datagrams = TransportHelper.TRANSPORT_TYPE.datagramChannelFactory();
        CompletableFuture<QuicByteStream> result = new CompletableFuture<>();
        new Bootstrap().group(loop).channelFactory(datagrams).handler(new ChannelInboundHandlerAdapter())
                .bind(0).addListener(f -> {
                    if (!f.isSuccess()) {
                        result.completeExceptionally(f.cause());
                        return;
                    }
                    DatagramChannel udp = (DatagramChannel) ((io.netty.channel.ChannelFuture) f).channel();
                    var socket = GameHost.socket(udp);
                    if (dropFromServer) {
                        udp.pipeline().addFirst(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                                io.netty.util.ReferenceCountUtil.release(msg);
                            }
                        });
                    }
                    CompletableFuture<QuicByteStream> attempt =
                            QuicClient.connect(GameHost.loop(loop), socket, target, fingerprint, config, early);
                    result.whenComplete((s, e) -> {
                        if (result.isCancelled()) {
                            attempt.cancel(false); // abandons the QUIC attempt, as a lost race does
                        }
                    });
                    attempt.whenComplete((s, e) -> {
                        if (e != null) {
                            result.completeExceptionally(e);
                            return;
                        }
                        stream = s;
                        if (!result.complete(s)) {
                            s.close(); // cancelled meanwhile
                        }
                    });
                });
        return result;
    }

    QuicByteStream stream() {
        return stream;
    }

    /** The loop QUIC runs on: the bridge channel registers there too, so nothing crosses threads. */
    @Override
    protected EventLoopGroup getEventLoopGroup() {
        return loop != null ? loop : super.getEventLoopGroup();
    }

    @Override
    protected ChannelFactory<? extends Channel> getChannelFactory() {
        return () -> new QuicBridgeChannel(stream);
    }
}
