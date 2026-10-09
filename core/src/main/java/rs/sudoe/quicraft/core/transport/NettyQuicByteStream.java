// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import rs.sudoe.quicraft.core.Protocol;

/** {@link QuicByteStream} over a Netty {@link QuicStreamChannel}. */
final class NettyQuicByteStream extends ChannelInboundHandlerAdapter implements QuicByteStream {
    private final QuicStreamChannel channel;
    private final AtomicBoolean closedNotified = new AtomicBoolean();
    private volatile Listener listener;

    NettyQuicByteStream(QuicStreamChannel channel) {
        this.channel = channel;
        channel.config().setAutoRead(false);
        channel.pipeline().addLast(this);
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) channel.parent().remoteSocketAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channel.parent().localSocketAddress();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void write(ByteBuffer data) {
        ByteBuf copy = channel.alloc().buffer(data.remaining());
        copy.writeBytes(data.duplicate());
        channel.write(copy).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
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
    public void setAutoRead(boolean autoRead) {
        channel.config().setAutoRead(autoRead);
    }

    @Override
    public void read() {
        channel.read();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen() && !closedNotified.get();
    }

    @Override
    public void close() {
        channel.parent().close(true, Protocol.CLOSE_NORMAL, Unpooled.EMPTY_BUFFER);
    }

    @Override
    public void execute(Runnable task) {
        channel.eventLoop().execute(task);
    }

    @Override
    public boolean inEventLoop() {
        return channel.eventLoop().inEventLoop();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            Listener l = listener;
            if (l != null && buf.isReadable()) {
                l.onData(buf.nioBuffer());
            }
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        Listener l = listener;
        if (l != null) {
            l.onWritabilityChanged(channel.isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof ChannelInputShutdownReadComplete) {
            // Peer sent FIN: like a TCP close (docs/protocol.md §8).
            notifyClosed(null);
            close();
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        notifyClosed(null);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        notifyClosed(cause);
        channel.parent().close(true, Protocol.CLOSE_INTERNAL_ERROR, Unpooled.EMPTY_BUFFER);
    }

    private void notifyClosed(Throwable cause) {
        if (closedNotified.compareAndSet(false, true)) {
            Listener l = listener;
            if (l != null) {
                l.onClosed(cause);
            }
        }
    }
}
