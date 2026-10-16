// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import rs.sudoe.quicraft.core.tls.FingerprintTrustManager;
import rs.sudoe.quicraft.core.tls.ServerIdentity;

/**
 * Test helper: a client and a server QUIC connection over loopback, built straight on Netty with
 * the /3 transport limits, handing every peer-created stream to a caller-supplied sink. No
 * QUICraft stream logic is involved, so lane tests can drive either end by hand.
 */
public final class LaneLoopback implements AutoCloseable {
    /** Receives each stream the peer created, on the connection's event loop. */
    public interface StreamSink extends Consumer<QuicStreamChannel> {}

    /** Knobs a test may change before {@link #open}. */
    public static final class Options {
        public TransportConfig server = TransportConfig.multistream();
        public TransportConfig client = TransportConfig.multistream();
        /** Raises quiche's stream limits so the application-level checks can be reached; -1 keeps them. */
        public int serverMaxStreamsBidi = -1;
        public int clientMaxStreamsBidi = -1;
        public int serverMaxStreamsUni = -1;
        public StreamSink serverSink = stream -> {};
        public StreamSink clientSink = stream -> {};
    }

    public final EventLoopGroup group;
    public final Channel serverUdp;
    public final Channel clientUdp;
    public final CompletableFuture<QuicChannel> serverConnection;
    public final QuicChannel clientConnection;
    /** The application error code the server's connection saw the client close with. */
    public final CompletableFuture<Integer> serverSawClose;
    /** The application error code the client's connection saw the server close with. */
    public final CompletableFuture<Integer> clientSawClose;

    private LaneLoopback(EventLoopGroup group, Channel serverUdp, Channel clientUdp, QuicChannel clientConnection,
            CompletableFuture<QuicChannel> serverConnection, CompletableFuture<Integer> serverSawClose,
            CompletableFuture<Integer> clientSawClose) {
        this.group = group;
        this.serverUdp = serverUdp;
        this.clientUdp = clientUdp;
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
        this.serverSawClose = serverSawClose;
        this.clientSawClose = clientSawClose;
    }

    public static LaneLoopback open(Options options) throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        EventLoopGroup group = Codecs.newGroup("lane-loopback", 2);
        CompletableFuture<QuicChannel> serverConnection = new CompletableFuture<>();
        CompletableFuture<Integer> serverSaw = new CompletableFuture<>();
        CompletableFuture<Integer> clientSaw = new CompletableFuture<>();
        try {
            QuicServerCodecBuilder server = Codecs.apply(new QuicServerCodecBuilder(), options.server, true);
            if (options.serverMaxStreamsBidi >= 0) {
                server.initialMaxStreamsBidirectional(options.serverMaxStreamsBidi);
            }
            if (options.serverMaxStreamsUni >= 0) {
                server.initialMaxStreamsUnidirectional(options.serverMaxStreamsUni);
                server.initialMaxStreamDataUnidirectional(8192);
            }
            io.netty.channel.ChannelHandler serverCodec = server
                    .sslContext(QuicSslContextBuilder.forServer(identity.privateKey(), null, identity.certificate())
                            .applicationProtocols("quicraft/3").build())
                    .tokenHandler(NoRetryTokenHandler.INSTANCE)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel ch) {
                            ch.pipeline().addLast(closeWatch(serverSaw));
                            serverConnection.complete(ch);
                        }
                    })
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel stream) {
                            stream.config().setAutoRead(false);
                            rs.sudoe.quicraft.core.multistream.ReadGrantAllocator.install(stream);
                            options.serverSink.accept(stream);
                        }
                    })
                    .build();
            Channel serverUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(serverCodec).bind(Loopback.anyLocal()).sync().channel();

            QuicClientCodecBuilder client = Codecs.apply(new QuicClientCodecBuilder(), options.client, false);
            if (options.clientMaxStreamsBidi >= 0) {
                client.initialMaxStreamsBidirectional(options.clientMaxStreamsBidi);
            }
            io.netty.channel.ChannelHandler clientCodec = client
                    .sslContext(QuicSslContextBuilder.forClient()
                            .trustManager(new FingerprintTrustManager(identity.fingerprint()))
                            .endpointIdentificationAlgorithm(null)
                            .applicationProtocols("quicraft/3").build())
                    .build();
            Channel clientUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(clientCodec).bind(0).sync().channel();
            QuicChannel clientConnection = QuicChannel.newBootstrap(clientUdp)
                    .handler(closeWatch(clientSaw))
                    .streamHandler(new ChannelInitializer<QuicStreamChannel>() {
                        @Override
                        protected void initChannel(QuicStreamChannel stream) {
                            stream.config().setAutoRead(false);
                            rs.sudoe.quicraft.core.multistream.ReadGrantAllocator.install(stream);
                            options.clientSink.accept(stream);
                        }
                    })
                    .remoteAddress(serverUdp.localAddress()).connect().get(10, TimeUnit.SECONDS);
            return new LaneLoopback(group, serverUdp, clientUdp, clientConnection, serverConnection,
                    serverSaw, clientSaw);
        } catch (Throwable t) {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            throw t;
        }
    }

    private static ChannelInboundHandlerAdapter closeWatch(CompletableFuture<Integer> seen) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof QuicConnectionCloseEvent) {
                    QuicConnectionCloseEvent close = (QuicConnectionCloseEvent) evt;
                    seen.complete(close.isApplicationClose() ? close.error() : -1);
                }
                ctx.fireUserEventTriggered(evt);
            }
        };
    }

    /** Opens a bidirectional stream from the client; the handler is its first pipeline entry. */
    public QuicStreamChannel clientStream(io.netty.channel.ChannelHandler handler) throws Exception {
        return clientConnection.createStream(QuicStreamType.BIDIRECTIONAL,
                new ChannelInitializer<QuicStreamChannel>() {
                    @Override
                    protected void initChannel(QuicStreamChannel stream) {
                        rs.sudoe.quicraft.core.multistream.ReadGrantAllocator.install(stream);
                        stream.pipeline().addLast(handler);
                    }
                }).get(10, TimeUnit.SECONDS);
    }

    public QuicStreamChannel clientStream() throws Exception {
        return clientStream(new ChannelInboundHandlerAdapter());
    }

    public QuicStreamChannel clientUniStream() throws Exception {
        return clientConnection.createStream(QuicStreamType.UNIDIRECTIONAL, new ChannelInboundHandlerAdapter())
                .get(10, TimeUnit.SECONDS);
    }

    /** Writes bytes to a stream and flushes. */
    public static void write(QuicStreamChannel stream, byte... bytes) {
        stream.writeAndFlush(Unpooled.wrappedBuffer(bytes));
    }

    @Override
    public void close() {
        try {
            clientConnection.close(true, 0, Unpooled.EMPTY_BUFFER).awaitUninterruptibly(2, TimeUnit.SECONDS);
        } finally {
            clientUdp.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            serverUdp.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }
}
