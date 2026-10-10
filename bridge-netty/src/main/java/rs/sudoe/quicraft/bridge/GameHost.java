// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import rs.sudoe.quicraft.core.transport.HostDatagramSocket;
import rs.sudoe.quicraft.core.transport.HostLoop;

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
