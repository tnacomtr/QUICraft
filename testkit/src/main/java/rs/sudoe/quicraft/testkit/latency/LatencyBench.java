// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit.latency;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import rs.sudoe.quicraft.bridge.GameHost;
import rs.sudoe.quicraft.bridge.QuicBridgeChannel;
import rs.sudoe.quicraft.core.tls.ServerIdentity;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.TransportConfig;
import rs.sudoe.quicraft.testkit.Args;

/**
 * In-process loopback ping-pong: how much latency each transport layer adds, without Docker,
 * netem or a game server. A 16-byte message goes out from a plain thread (as MCProtocolLib and
 * the game send), the server echoes it, and the client times the round trip. Between pings the
 * client idles for {@code --gap-us}, so threads park as they do between game ticks.
 *
 * <p>Modes: {@code tcp} (Netty NIO both sides), {@code quic} (core QuicByteStream both sides,
 * echo on the QUIC thread), {@code bridge} (QUIC with a bridge channel on a Netty NIO loop at
 * both ends, the Velocity/mod path before the hosted transport), {@code hosted} (QUIC, UDP socket
 * and bridge channel on one game NIO loop at each end: no thread hop).
 */
public final class LatencyBench {
    private static final int SIZE = 16;
    /** Trace points for one round trip (hosted mode): nanoTime per point, and datagram counts. */
    static final String[] POINTS = {"send", "c.bridge.write", "c.udp.flush", "s.udp.read", "s.bridge.read",
        "s.udp.flush", "c.udp.read", "c.bridge.read", "recv"};
    static final long[] T = new long[POINTS.length];
    static final java.util.concurrent.atomic.AtomicInteger C_OUT = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger S_OUT = new java.util.concurrent.atomic.AtomicInteger();
    static boolean trace;

    static void mark(int point) {
        if (trace && T[point] == 0) {
            T[point] = System.nanoTime();
        }
    }

    /** First-in-pipeline game handler that marks reads and flushes on a UDP or bridge channel. */
    static final class Marker extends io.netty.channel.ChannelDuplexHandler {
        private final int read;
        private final int out;
        private final java.util.concurrent.atomic.AtomicInteger counter;

        Marker(int read, int out, java.util.concurrent.atomic.AtomicInteger counter) {
            this.read = read;
            this.out = out;
            this.counter = counter;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (read >= 0) {
                mark(read);
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) {
            if (counter != null) {
                counter.incrementAndGet();
            } else if (out >= 0) {
                mark(out);
            }
            ctx.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (counter != null && out >= 0) {
                mark(out);
            }
            ctx.flush();
        }
    }
    private static TransportConfig config = TransportConfig.DEFAULT;

    private LatencyBench() {}

    public static int run(Args args) throws Exception {
        int count = args.integer("count", 3000);
        int warmup = args.integer("warmup", 1000);
        long gapNanos = TimeUnit.MICROSECONDS.toNanos(args.integer("gap-us", 2000));
        config = TransportConfig.builder()
                .congestionControl(TransportConfig.CongestionControl.valueOf(args.string("cc", "bbr").toUpperCase()))
                .build();
        String[] modes = args.string("modes", "tcp,quic,bridge,hosted").split(",");
        for (String mode : modes) {
            double[] rtt;
            try (Echo echo = switch (mode) {
                case "tcp" -> tcp();
                case "quic" -> quic(false);
                case "bridge" -> quic(true);
                case "hosted" -> hosted();
                default -> throw new IllegalArgumentException("unknown mode " + mode);
            }) {
                rtt = measure(echo, warmup, count, gapNanos);
            }
            Arrays.sort(rtt);
            System.out.printf("%-7s n=%d gap=%dus  p50=%.1fus p90=%.1fus p99=%.1fus min=%.1fus%n", mode, count,
                    TimeUnit.NANOSECONDS.toMicros(gapNanos), pct(rtt, 0.5), pct(rtt, 0.9), pct(rtt, 0.99), rtt[0]);
        }
        return 0;
    }

    private static double pct(double[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.round(p * (sorted.length - 1)))];
    }

    private static double[] measure(Echo echo, int warmup, int count, long gapNanos) throws Exception {
        double[] out = new double[count];
        double[][] segments = new double[POINTS.length - 1][count];
        int[] cOut = new int[count];
        int[] sOut = new int[count];
        for (int i = -warmup; i < count; i++) {
            Arrays.fill(T, 0);
            C_OUT.set(0);
            S_OUT.set(0);
            long start = System.nanoTime();
            T[0] = start;
            echo.send();
            echo.replies.take();
            long end = System.nanoTime();
            T[POINTS.length - 1] = end;
            if (i >= 0) {
                out[i] = (end - start) / 1000.0;
                for (int p = 0; p < POINTS.length - 1; p++) {
                    segments[p][i] = (T[p + 1] - T[p]) / 1000.0;
                }
                cOut[i] = C_OUT.get();
                sOut[i] = S_OUT.get();
            }
            LockSupport.parkNanos(gapNanos);
            // Datagrams sent in the gap (ACKs, timers) count toward the next round.
        }
        if (trace) {
            for (int p = 0; p < POINTS.length - 1; p++) {
                double[] seg = segments[p].clone();
                Arrays.sort(seg);
                System.out.printf("   %-15s -> %-15s p50=%7.1fus p90=%7.1fus%n", POINTS[p], POINTS[p + 1],
                        pct(seg, 0.5), pct(seg, 0.9));
            }
            System.out.printf("   datagrams per round incl. gap: client %.2f, server %.2f%n",
                    Arrays.stream(cOut).average().orElse(0), Arrays.stream(sOut).average().orElse(0));
        }
        return out;
    }

    private abstract static class Echo implements AutoCloseable {
        final SynchronousQueue<Long> replies = new SynchronousQueue<>();
        private int received;

        abstract void send();

        /** Called with each chunk the client receives; signals once a whole message is back. */
        void onClientBytes(int n) {
            received += n;
            while (received >= SIZE) {
                received -= SIZE;
                try {
                    replies.put(System.nanoTime());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static EventLoopGroup nio(int threads) {
        return new MultiThreadIoEventLoopGroup(threads, NioIoHandler.newFactory());
    }

    private static final class EchoHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.writeAndFlush(msg);
        }
    }

    private static Echo tcp() throws Exception {
        EventLoopGroup server = nio(1);
        EventLoopGroup client = nio(1);
        trace = true;
        Channel listener = new ServerBootstrap().group(server).channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel c) {
                        // Server: read = s.udp.read and s.bridge.read; flush = s.udp.flush.
                        c.pipeline().addLast(new Marker(3, 5, S_OUT), new Marker(4, -1, null), new EchoHandler());
                    }
                })
                .bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        Channel[] ch = new Channel[1];
        Echo echo = new Echo() {
            @Override
            void send() {
                ch[0].writeAndFlush(Unpooled.wrappedBuffer(new byte[SIZE]));
            }

            @Override
            public void close() {
                ch[0].close().syncUninterruptibly();
                listener.close().syncUninterruptibly();
                client.shutdownGracefully(0, 0, TimeUnit.SECONDS);
                server.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
        };
        ch[0] = new Bootstrap().group(client).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel c) {
                        // Client: write = c.bridge.write, flush = c.udp.flush; read = both reads.
                        c.pipeline().addLast(new Marker(6, 2, C_OUT), new Marker(7, 1, null), new ClientCounter(echo));
                    }
                })
                .connect(listener.localAddress()).sync().channel();
        return echo;
    }

    private static final class ClientCounter extends ChannelInboundHandlerAdapter {
        private final Echo echo;

        ClientCounter(Echo echo) {
            this.echo = echo;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            echo.onClientBytes(buf.readableBytes());
            buf.release();
        }
    }

    private static Echo hosted() throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        EventLoopGroup serverGroup = nio(1);
        EventLoopGroup clientGroup = nio(1);
        EventLoop serverLoop = serverGroup.next();
        EventLoop clientLoop = clientGroup.next();
        trace = true;
        DatagramChannel serverUdp = (DatagramChannel) new Bootstrap().group(serverLoop)
                .channel(NioDatagramChannel.class).handler(new Marker(3, 5, S_OUT))
                .bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        QuicServer server = QuicServer.bind(GameHost.loop(serverLoop), GameHost.socket(serverUdp), identity,
                config, stream -> {
                    QuicBridgeChannel ch = new QuicBridgeChannel(stream);
                    ch.pipeline().addLast(new Marker(4, -1, null), new EchoHandler());
                    serverLoop.register(ch);
                });
        DatagramChannel clientUdp = (DatagramChannel) new Bootstrap().group(clientLoop)
                .channel(NioDatagramChannel.class).handler(new Marker(6, 2, C_OUT))
                .bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
        QuicByteStream stream = QuicClient.connect(GameHost.loop(clientLoop), GameHost.socket(clientUdp),
                serverUdp.localAddress(), identity.fingerprint(), config).get(5, TimeUnit.SECONDS);
        QuicBridgeChannel ch = new QuicBridgeChannel(stream);
        Echo echo = new Echo() {
            @Override
            void send() {
                ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[SIZE]));
            }

            @Override
            public void close() {
                stream.close();
                server.close();
                clientGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
                serverGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
        };
        ch.pipeline().addLast(new Marker(7, 1, null), new ClientCounter(echo));
        clientLoop.register(ch).sync();
        return echo;
    }

    private static Echo quic(boolean bridged) throws Exception {
        ServerIdentity identity = ServerIdentity.generate();
        EventLoopGroup game = bridged ? nio(2) : null;
        QuicServer server = QuicServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), identity,
                config, stream -> {
                    if (bridged) {
                        QuicBridgeChannel ch = new QuicBridgeChannel(stream);
                        ch.pipeline().addLast(new EchoHandler());
                        game.register(ch);
                    } else {
                        stream.setListener(new QuicByteStream.Listener() {
                            @Override
                            public void onData(ByteBuffer data) {
                                stream.write(data);
                                stream.flush();
                            }

                            @Override
                            public void onWritabilityChanged(boolean writable) {
                            }

                            @Override
                            public void onClosed(Throwable cause) {
                            }
                        });
                        stream.setAutoRead(true);
                    }
                });
        QuicByteStream stream = QuicClient.connect(server.localAddress(), identity.fingerprint(),
                config).get(5, TimeUnit.SECONDS);
        Echo echo;
        if (bridged) {
            QuicBridgeChannel ch = new QuicBridgeChannel(stream);
            echo = new Echo() {
                @Override
                void send() {
                    ch.writeAndFlush(Unpooled.wrappedBuffer(new byte[SIZE]));
                }

                @Override
                public void close() {
                    stream.close();
                    server.close();
                    game.shutdownGracefully(0, 0, TimeUnit.SECONDS);
                }
            };
            ch.pipeline().addLast(new ClientCounter(echo));
            game.register(ch).sync();
        } else {
            echo = new Echo() {
                @Override
                void send() {
                    stream.write(ByteBuffer.wrap(new byte[SIZE]));
                    stream.flush();
                }

                @Override
                public void close() {
                    stream.close();
                    server.close();
                }
            };
            Echo e = echo;
            stream.setListener(new QuicByteStream.Listener() {
                @Override
                public void onData(ByteBuffer data) {
                    e.onClientBytes(data.remaining());
                }

                @Override
                public void onWritabilityChanged(boolean writable) {
                }

                @Override
                public void onClosed(Throwable cause) {
                }
            });
            stream.setAutoRead(true);
        }
        return echo;
    }
}
