// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * Core's (relocated) Netty channel over a {@link HostDatagramSocket}: what the QUIC codec sits
 * on in the hosted transport. Datagrams are copied across, so no buffer is shared between the
 * two Netty copies. Registered on a {@link HostedEventLoop} for the same host loop.
 */
final class HostedDatagramChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final HostDatagramSocket socket;
    private final ChannelConfig config = new DefaultChannelConfig(this);
    private volatile boolean open = true;
    /** Loop only: a flush stopped at an unwritable socket. */
    private boolean flushPending;

    HostedDatagramChannel(HostDatagramSocket socket) {
        super(null);
        this.socket = socket;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return open && isRegistered();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
                promise.setFailure(new UnsupportedOperationException("hosted datagram channel"));
            }
        };
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof HostedEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return socket.localAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void doRegister() {
        socket.start(new Receiver());
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("the host socket is already bound");
    }

    @Override
    protected void doDisconnect() {
        doClose();
    }

    @Override
    protected void doClose() {
        if (open) {
            open = false;
            socket.close();
        }
    }

    @Override
    protected void doBeginRead() {
        // The host pushes every datagram; QUIC never pauses reading on the socket.
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof DatagramPacket) {
            return msg;
        }
        throw new UnsupportedOperationException("unsupported message type: " + msg.getClass().getName());
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        boolean wrote = false;
        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                flushPending = false;
                break;
            }
            if (!socket.isWritable()) {
                flushPending = true;
                break;
            }
            DatagramPacket packet = (DatagramPacket) msg;
            ByteBuf content = packet.content();
            socket.write(content.nioBuffer(content.readerIndex(), content.readableBytes()), packet.recipient());
            wrote = true;
            in.remove();
        }
        if (wrote) {
            socket.flush();
        }
    }

    private final class Receiver implements HostDatagramSocket.Receiver {
        @Override
        public void onDatagram(ByteBuffer data, InetSocketAddress sender) {
            if (!open) {
                return;
            }
            // Direct: quiche reads it through its memory address.
            ByteBuf copy = alloc().directBuffer(data.remaining());
            copy.writeBytes(data);
            pipeline().fireChannelRead(new DatagramPacket(copy, socket.localAddress(), sender));
        }

        @Override
        public void onReadComplete() {
            if (open) {
                pipeline().fireChannelReadComplete();
            }
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
            if (writable && flushPending && open) {
                unsafe().flush();
            }
        }

        @Override
        public void onClosed() {
            if (open) {
                unsafe().close(voidPromise());
            }
        }
    }
}
