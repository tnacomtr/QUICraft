// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import rs.sudoe.quicraft.core.routing.RoutingTable;
import rs.sudoe.quicraft.core.tls.Fingerprint;
import rs.sudoe.quicraft.core.transport.HostDatagramSocket;
import rs.sudoe.quicraft.core.transport.HostLoop;
import rs.sudoe.quicraft.core.transport.QuicByteStream;
import rs.sudoe.quicraft.core.transport.QuicClient;
import rs.sudoe.quicraft.core.transport.QuicServer;
import rs.sudoe.quicraft.core.transport.V3Connection;
import rs.sudoe.quicraft.core.transport.EarlyFlight;
import rs.sudoe.quicraft.core.transport.TransportConfig;

/**
 * The game's (or proxy's) Netty as a host for core's QUIC (hosted transport, docs/protocol.md
 * §11). With the UDP socket, QUIC and the {@link QuicBridgeChannel} all on one game event loop,
 * a packet crosses no thread between the wire and the game pipeline, as with TCP.
 */
public final class GameHost {
    private GameHost() {}

    public static HostLoop loop(EventLoop loop) {
        return new HostLoop() {
            @Override
            public boolean inEventLoop(Thread thread) {
                return loop.inEventLoop(thread);
            }

            @Override
            public void execute(Runnable task) {
                loop.execute(task);
            }

            @Override
            public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
                ScheduledFuture<?> future = loop.schedule(task, delay, unit);
                return () -> future.cancel(false);
            }
        };
    }

    /**
     * UDP socket buffer size requested for QUIC sockets, best effort: the kernel caps it (Linux:
     * {@code net.core.rmem_max} / {@code wmem_max}, often 208 KiB). UDP has no flow control, so a
     * burst bigger than the receive buffer while the loop is busy is dropped. System property
     * {@code quicraft.udpBufferBytes}; 0 leaves the kernel default.
     */
    public static final int UDP_BUFFER_BYTES = Integer.getInteger("quicraft.udpBufferBytes", 4 << 20);

    static final int MAX_DATAGRAMS_PER_READ = 128;

    /**
     * A bound game {@link DatagramChannel}, registered on the loop QUIC will run on, as a
     * {@link HostDatagramSocket}. Datagrams that arrive before core starts the socket are
     * dropped; QUIC retransmits. Asks for {@link #UDP_BUFFER_BYTES} socket buffers.
     */
    public static HostDatagramSocket socket(DatagramChannel channel) {
        if (UDP_BUFFER_BYTES > 0) {
            try {
                channel.config().setReceiveBufferSize(UDP_BUFFER_BYTES);
                channel.config().setSendBufferSize(UDP_BUFFER_BYTES);
            } catch (RuntimeException e) {
                // Best effort: the kernel default still works, with more drops under bursts.
            }
        }
        // Drain more of the socket per loop pass than Netty's default 16 datagrams; together with
        // the bridge's sliced delivery this keeps a burst in quiche's buffer, not the kernel's.
        io.netty.channel.RecvByteBufAllocator allocator = channel.config().getRecvByteBufAllocator();
        if (allocator instanceof io.netty.channel.MaxMessagesRecvByteBufAllocator) {
            ((io.netty.channel.MaxMessagesRecvByteBufAllocator) allocator).maxMessagesPerRead(MAX_DATAGRAMS_PER_READ);
        }
        SocketHandler handler = new SocketHandler(channel);
        channel.pipeline().addLast(handler);
        return handler;
    }

    /**
     * The datagram channel type that runs on the same kind of event loop as {@code socketChannel}
     * (in Netty 4.2 a channel only registers on a loop of its own transport): Epoll, KQueue or
     * NIO, found by name in the socket channel's package. Throws if there is none.
     */
    @SuppressWarnings("unchecked")
    public static ChannelFactory<? extends DatagramChannel> datagramChannelsLike(Class<? extends Channel> socketChannel)
            throws ClassNotFoundException {
        String name = socketChannel.getName();
        if (!name.endsWith("SocketChannel")) {
            throw new ClassNotFoundException("no datagram channel for " + name);
        }
        String datagram = name.substring(0, name.length() - "SocketChannel".length()) + "DatagramChannel";
        Class<?> type = Class.forName(datagram, false, socketChannel.getClassLoader());
        if (!DatagramChannel.class.isAssignableFrom(type)) {
            throw new ClassNotFoundException(datagram + " is not a DatagramChannel");
        }
        return new ReflectiveChannelFactory<>((Class<? extends DatagramChannel>) type);
    }

    private static final Logger LOG = Logger.getLogger("QUICraft");

    /**
     * SO_REUSEPORT balances unicast UDP over several sockets on Linux, and only there: macOS and
     * the BSDs hash the local address alone (one socket would starve) and Windows has no
     * SO_REUSEPORT (docs/protocol.md §11).
     */
    private static final boolean REUSEPORT_BALANCES = System.getProperty("os.name", "").startsWith("Linux");

    private static final java.util.concurrent.atomic.AtomicBoolean SOCKET_COUNT_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean ONE_SOCKET_WARNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The number of UDP sockets a listener binds, from the platform config's {@code udp-sockets}
     * value: {@code "auto"} (or nothing) is {@link QuicServer#defaultSocketCount()}, an integer
     * 1..{@link QuicServer#MAX_UDP_SOCKETS} is that many, anything else is {@code "auto"} with one
     * WARNING per JVM (docs/protocol.md §11). Shared by the platforms, so they read the setting
     * the same way.
     */
    public static int socketCount(String value) {
        String setting = value == null ? "" : value.trim();
        if (setting.isEmpty() || "auto".equalsIgnoreCase(setting)) {
            return QuicServer.defaultSocketCount();
        }
        try {
            int parsed = Integer.parseInt(setting);
            if (parsed >= 1 && parsed <= QuicServer.MAX_UDP_SOCKETS) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the warning
        }
        if (SOCKET_COUNT_WARNING.compareAndSet(false, true)) {
            LOG.warning("Invalid udp-sockets=" + value + " (expected auto or 1.." + QuicServer.MAX_UDP_SOCKETS
                    + "); using the default");
        }
        return QuicServer.defaultSocketCount();
    }

    /**
     * SO_REUSEPORT as a channel option for the transport of {@code datagramChannelClass}, or null
     * where that transport has none.
     *
     * <p>The constant is transport-specific and lives in the transport's own jar
     * ({@code EpollChannelOption}, {@code KQueueChannelOption}, the io_uring
     * {@code IoUringChannelOption} in {@code io.netty.channel.uring} on Netty 4.2 or
     * {@code io.netty.incubator.channel.uring} on 4.1), which the bridge never compiles against.
     * It is found reflectively, by name, in the datagram channel class's own package
     * ({@code EpollDatagramChannel} names {@code EpollChannelOption}). NIO has no such constant:
     * its {@code NioChannelOption.of} wraps {@code StandardSocketOptions.SO_REUSEPORT}, which
     * exists only on JDK 9+ and is looked up reflectively too (this module is Java 8 bytecode).
     *
     * <p>Null means "no several-socket bind": the caller binds one socket, as before.
     */
    public static ChannelOption<Boolean> reusePortOption(Class<? extends Channel> datagramChannelClass) {
        String name = datagramChannelClass.getName();
        if (!name.endsWith("DatagramChannel")) {
            return null;
        }
        String optionName = name.substring(0, name.length() - "DatagramChannel".length()) + "ChannelOption";
        Class<?> optionClass;
        try {
            optionClass = Class.forName(optionName, false, datagramChannelClass.getClassLoader());
        } catch (Throwable none) {
            return null;
        }
        try {
            Object option = optionClass.getField("SO_REUSEPORT").get(null);
            if (option instanceof ChannelOption) {
                @SuppressWarnings("unchecked")
                ChannelOption<Boolean> reusePort = (ChannelOption<Boolean>) option;
                return reusePort;
            }
        } catch (Throwable none) {
            // NioChannelOption declares no such field; it wraps a JDK SocketOption instead.
        }
        return nioReusePortOption(optionClass);
    }

    /** {@code NioChannelOption.of(StandardSocketOptions.SO_REUSEPORT)}, both found reflectively. */
    @SuppressWarnings("unchecked")
    private static ChannelOption<Boolean> nioReusePortOption(Class<?> optionClass) {
        if (!optionClass.getName().equals("io.netty.channel.socket.nio.NioChannelOption")) {
            return null;
        }
        try {
            Object option = Class.forName("java.net.StandardSocketOptions").getField("SO_REUSEPORT").get(null);
            if (option instanceof java.net.SocketOption) {
                return io.netty.channel.socket.nio.NioChannelOption.of((java.net.SocketOption<Boolean>) option);
            }
        } catch (Throwable unavailable) {
            // JDK 8, or a platform whose JDK has no SO_REUSEPORT.
        }
        return null;
    }

    /**
     * The UDP sockets a hosted QUIC listener runs on: one per distinct loop of the platform's
     * worker group, so the kernel's SO_REUSEPORT hash spreads the port's datagrams over as many
     * game event loops (docs/protocol.md §11). Hand each pair to
     * {@link QuicServer#bind(java.util.List, java.util.List, ServerIdentity, TransportConfig, QuicServer.StreamAcceptor)}
     * and register accepted streams on {@link #acceptingLoop()}.
     */
    public static final class HostedSockets implements AutoCloseable {
        private final List<EventLoop> loops;
        private final List<HostDatagramSocket> sockets;
        private final List<HostLoop> hostLoops;

        private HostedSockets(List<EventLoop> loops, List<HostDatagramSocket> sockets) {
            this.loops = Collections.unmodifiableList(loops);
            this.sockets = Collections.unmodifiableList(sockets);
            List<HostLoop> hosts = new ArrayList<>(loops.size());
            for (EventLoop loop : loops) {
                hosts.add(loop(loop));
            }
            this.hostLoops = Collections.unmodifiableList(hosts);
        }

        /** UDP sockets bound, in bind order: always at least one. */
        public int size() {
            return sockets.size();
        }

        /** The game loops the sockets run on, one each, in the same order. */
        public List<EventLoop> loops() {
            return loops;
        }

        /** {@link #loops()} as core's host loops, for {@link QuicServer#bind}. */
        public List<HostLoop> hostLoops() {
            return hostLoops;
        }

        /** {@link #size()} bound sockets, in bind order, for {@link QuicServer#bind}. */
        public List<HostDatagramSocket> hostSockets() {
            return sockets;
        }

        /**
         * The loop of the socket whose codec is calling. The acceptor runs on the accepting
         * socket's loop, so a stream registered on the returned loop stays on that socket's loop,
         * as with a single socket. The first loop when the caller is none of them.
         */
        public EventLoop acceptingLoop() {
            Thread current = Thread.currentThread();
            for (EventLoop loop : loops) {
                if (loop.inEventLoop(current)) {
                    return loop;
                }
            }
            return loops.get(0);
        }

        /** Closes what is still bound; a failed core bind closes what it registered itself. */
        @Override
        public void close() {
            for (HostDatagramSocket socket : sockets) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                    // best effort: the caller is on a failure path
                }
            }
        }
    }

    /**
     * Binds the platform's QUIC UDP sockets (hosted transport, docs/protocol.md §11): up to
     * {@code socketCount} of {@code datagrams}' type, one per distinct loop of {@code workers},
     * each with SO_REUSEPORT, the first to {@code address} and the rest to the first's actual
     * local address. Fewer when the group has fewer loops.
     *
     * <p>Fails safe, twice over. A later socket that cannot be created or bound (no SO_REUSEPORT
     * for this transport, the transport or the kernel refusing the option, the port taken by
     * something else) is closed and the bind continues with the sockets already bound, so a
     * listener that cannot use several always has one. Only the first socket failing throws, which
     * is exactly today's failure to listen (the platform turns QUIC off and leaves TCP alone). The
     * first socket keeps the option when it was applied to it, so it is not inert with one socket:
     * the first socket is configured with SO_REUSEPORT before it binds, and any process of the same
     * OS user can then bind the same UDP port with SO_REUSEPORT too and take a share of the traffic
     * (docs/server-security.md).
     */
    public static HostedSockets bindHosted(EventLoopGroup workers, int socketCount, InetSocketAddress address,
            ChannelFactory<? extends DatagramChannel> datagrams) throws Exception {
        List<EventLoop> loops = loops(workers, socketCount);
        DatagramChannel first = datagrams.newChannel();
        ChannelOption<Boolean> reusePort = null;
        int count = 1;
        if (loops.size() > 1 && !REUSEPORT_BALANCES) {
            oneSocket(socketCount + " UDP sockets were configured, but SO_REUSEPORT balances unicast UDP on"
                    + " Linux only; binding one UDP socket");
        } else if (loops.size() > 1) {
            reusePort = reusePortOption(first.getClass());
            if (reusePort == null) {
                oneSocket("no SO_REUSEPORT for " + first.getClass().getSimpleName() + "; binding one UDP socket");
            } else if (!setReusePort(first, reusePort)) {
                oneSocket(first.getClass().getSimpleName() + " refused SO_REUSEPORT; binding one UDP socket");
                reusePort = null;
            } else {
                count = loops.size();
            }
        }
        List<EventLoop> boundLoops = new ArrayList<>(count);
        List<HostDatagramSocket> boundSockets = new ArrayList<>(count);
        boundLoops.add(loops.get(0));
        boundSockets.add(bind(loops.get(0), first, address));
        InetSocketAddress local = boundSockets.get(0).localAddress();
        for (int i = 1; i < count; i++) {
            DatagramChannel next = null;
            try {
                next = datagrams.newChannel();
                if (!setReusePort(next, reusePort)) {
                    throw new IOException("SO_REUSEPORT was refused");
                }
                HostDatagramSocket bound = bind(loops.get(i), next, local);
                boundLoops.add(loops.get(i));
                boundSockets.add(bound);
            } catch (Throwable failure) {
                closeQuietly(next);
                if (failure instanceof VirtualMachineError) {
                    throw (VirtualMachineError) failure;
                }
                LOG.info("QUICraft: socket " + (i + 1) + " of " + count + " could not be bound (" + failure
                        + "); QUIC listening on " + boundSockets.size() + " socket(s)");
                break;
            }
        }
        return new HostedSockets(boundLoops, boundSockets);
    }

    /**
     * Up to {@code count} distinct loops of {@code workers}, one per UDP socket, so QUIC spreads
     * over as many game event loops. Fewer when the group has fewer loops; never empty.
     */
    private static List<EventLoop> loops(EventLoopGroup workers, int count) {
        int wanted = Math.max(1, count);
        List<EventLoop> loops = new ArrayList<>(wanted);
        // An EventLoopGroup iterates its children as EventExecutor (the interface's legacy
        // typing); every child of an EventLoopGroup is an EventLoop.
        for (io.netty.util.concurrent.EventExecutor executor : workers) {
            if (executor instanceof EventLoop) {
                loops.add((EventLoop) executor);
                if (loops.size() == wanted) {
                    break;
                }
            }
        }
        if (loops.isEmpty()) {
            loops.add(workers.next());
        }
        return loops;
    }

    /** Registers {@code channel} on {@code loop}, binds it to {@code address}, wraps it; closes it on failure. */
    private static HostDatagramSocket bind(EventLoop loop, DatagramChannel channel, InetSocketAddress address)
            throws Exception {
        try {
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter());
            loop.register(channel).sync();
            channel.bind(address).sync();
            return socket(channel);
        } catch (Exception failure) {
            closeQuietly(channel);
            throw failure;
        } catch (Error failure) {
            closeQuietly(channel);
            throw failure;
        }
    }

    private static boolean setReusePort(DatagramChannel channel, ChannelOption<Boolean> reusePort) {
        try {
            return channel.config().setOption(reusePort, Boolean.TRUE);
        } catch (Throwable refused) {
            return false;
        }
    }

    private static void closeQuietly(DatagramChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            if (channel.isRegistered()) {
                channel.close();
            } else {
                // Never registered: Netty's close() needs the loop it would run on.
                channel.unsafe().closeForcibly();
            }
        } catch (Throwable ignored) {
            // the bind failure is what the caller must see
        }
    }

    private static void oneSocket(String why) {
        if (ONE_SOCKET_WARNING.compareAndSet(false, true)) {
            LOG.info("QUICraft: " + why + " (docs/protocol.md §11)");
        }
    }

    /**
     * Opens a QUIC connection hosted on {@code loop}: binds a UDP socket of the given type there
     * (wildcard address of the server's family, any port) and runs {@link QuicClient} over it.
     * Cancelling the result cancels the attempt and closes the socket, also when it is still
     * binding; a stream that arrives after the cancel is closed.
     */
    public static CompletableFuture<QuicByteStream> connect(EventLoop loop,
            ChannelFactory<? extends DatagramChannel> datagrams, InetSocketAddress remote, Fingerprint fingerprint,
            TransportConfig config) {
        return connect(loop, datagrams, remote, fingerprint, config, null);
    }

    /** As above, with the join's 0-RTT request ({@link QuicClient}); null for none. */
    public static CompletableFuture<QuicByteStream> connect(EventLoop loop,
            ChannelFactory<? extends DatagramChannel> datagrams, InetSocketAddress remote, Fingerprint fingerprint,
            TransportConfig config, EarlyFlight early) {
        CompletableFuture<QuicByteStream> result = new CompletableFuture<>();
        InetSocketAddress local;
        try {
            local = new InetSocketAddress(InetAddress.getByAddress(
                    new byte[remote.getAddress() instanceof Inet6Address ? 16 : 4]), 0);
        } catch (UnknownHostException e) {
            result.completeExceptionally(e);
            return result;
        }
        ChannelFuture bound = new Bootstrap().group(loop).channelFactory(datagrams)
                .handler(new ChannelInboundHandlerAdapter()).bind(local);
        bound.addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                f.channel().close();
                result.completeExceptionally(f.cause());
                return;
            }
            if (result.isDone()) {
                f.channel().close(); // cancelled while binding
                return;
            }
            CompletableFuture<QuicByteStream> attempt = QuicClient.connect(loop(loop),
                    socket((DatagramChannel) f.channel()), remote, fingerprint, config, early);
            result.whenComplete((s, e) -> {
                if (result.isCancelled()) {
                    attempt.cancel(false);
                }
            });
            attempt.whenComplete((stream, error) -> {
                if (error != null) {
                    result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                } else if (!result.complete(stream)) {
                    stream.close();
                }
            });
        });
        return result;
    }

    /**
     * Opens a {@code quicraft/3} connection hosted on {@code loop} (docs/protocol.md 13.1): as
     * {@link #connect(EventLoop, ChannelFactory, InetSocketAddress, Fingerprint, TransportConfig,
     * EarlyFlight)} but through {@link QuicClient#connectV3}, so the result is a
     * {@link V3Connection} on the game's own event loop. {@code table} is the client's routing table
     * for its protocol number, {@code bypassResumption} 12.16's full-handshake re-bind.
     */
    public static CompletableFuture<V3Connection> connectV3(EventLoop loop,
            ChannelFactory<? extends DatagramChannel> datagrams, InetSocketAddress remote, Fingerprint fingerprint,
            RoutingTable table, TransportConfig config, EarlyFlight early, boolean bypassResumption) {
        CompletableFuture<V3Connection> result = new CompletableFuture<>();
        InetSocketAddress local;
        try {
            local = new InetSocketAddress(InetAddress.getByAddress(
                    new byte[remote.getAddress() instanceof Inet6Address ? 16 : 4]), 0);
        } catch (UnknownHostException e) {
            result.completeExceptionally(e);
            return result;
        }
        ChannelFuture bound = new Bootstrap().group(loop).channelFactory(datagrams)
                .handler(new ChannelInboundHandlerAdapter()).bind(local);
        bound.addListener((ChannelFuture f) -> {
            if (!f.isSuccess()) {
                f.channel().close();
                result.completeExceptionally(f.cause());
                return;
            }
            if (result.isDone()) {
                f.channel().close(); // cancelled while binding
                return;
            }
            CompletableFuture<V3Connection> attempt = QuicClient.connectV3(loop(loop),
                    socket((DatagramChannel) f.channel()), remote, fingerprint, table, config, early,
                    bypassResumption);
            result.whenComplete((s, e) -> {
                if (result.isCancelled()) {
                    attempt.cancel(false);
                }
            });
            attempt.whenComplete((stream, error) -> {
                if (error != null) {
                    result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                } else if (!result.complete(stream)) {
                    stream.close();
                }
            });
        });
        return result;
    }

    private static final class SocketHandler extends ChannelInboundHandlerAdapter implements HostDatagramSocket {
        private final DatagramChannel channel;
        private Receiver receiver;

        SocketHandler(DatagramChannel channel) {
            this.channel = channel;
        }

        @Override
        public InetSocketAddress localAddress() {
            return channel.localAddress();
        }

        @Override
        public void start(Receiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public void write(ByteBuffer data, InetSocketAddress recipient) {
            ByteBuf copy = channel.alloc().directBuffer(data.remaining());
            copy.writeBytes(data);
            channel.write(new DatagramPacket(copy, recipient), channel.voidPromise());
        }

        @Override
        public void flush() {
            channel.flush();
        }

        @Override
        public boolean isWritable() {
            return channel.isWritable();
        }

        @Override
        public void close() {
            channel.close();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof DatagramPacket)) {
                ctx.fireChannelRead(msg);
                return;
            }
            DatagramPacket packet = (DatagramPacket) msg;
            try {
                Receiver r = receiver;
                ByteBuf content = packet.content();
                if (r != null && content.isReadable()) {
                    r.onDatagram(content.nioBuffer(content.readerIndex(), content.readableBytes()), packet.sender());
                }
            } finally {
                packet.release();
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            Receiver r = receiver;
            if (r != null) {
                r.onReadComplete();
            }
            ctx.fireChannelReadComplete();
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            Receiver r = receiver;
            if (r != null) {
                r.onWritabilityChanged(ctx.channel().isWritable());
            }
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Receiver r = receiver;
            if (r != null) {
                r.onClosed();
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // A failed send (e.g. ICMP unreachable surfacing as PortUnreachableException) is
            // QUIC's business: it times out or retransmits. Keep the socket open.
        }
    }
}
