// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.bridge;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import rs.sudoe.quicraft.core.transport.QuicByteStream;

/**
 * A {@link Channel} in the game's (or proxy's) own Netty that carries a QUICraft QUIC stream, so
 * the vanilla pipeline runs on it unchanged (CLAUDE.md "Architecture"). The game's Netty and
 * QUICraft's relocated Netty never share objects: bytes are copied across as
 * {@link ByteBuffer}s, and every callback hops onto this channel's event loop.
 *
 * <p>Register it on the game's event loop group like any channel. It is active as soon as it is
 * registered. TCP options are accepted and ignored.
 */
public final class QuicBridgeChannel extends AbstractChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);

    private final QuicByteStream stream;
    private final Config config = new Config(this);
    private volatile boolean open = true;
    private volatile boolean listening;
    /** Event loop only: data held back while reading is paused (auto-read off, no read()). */
    private final java.util.ArrayDeque<ByteBuf> held = new java.util.ArrayDeque<>();
    /** Event loop only: a read() is outstanding while auto-read is off. */
    private boolean readRequested;

    public QuicBridgeChannel(Channel parent, QuicByteStream stream) {
        super(parent);
        this.stream = stream;
    }

    public QuicBridgeChannel(QuicByteStream stream) {
        this(null, stream);
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
        return open && isRegistered() && stream.isOpen();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new BridgeUnsafe();
    }

    /** Any event loop works: this channel does no I/O of its own. */
    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return stream.localAddress();
    }

    /** The player's UDP address, so IP bans and forwarding keep working. */
    @Override
    protected SocketAddress remoteAddress0() {
        return stream.remoteAddress();
    }

    /** Deprecated in Netty 4.2 but still called by its default register path; current in 4.1. */
    @Override
    @SuppressWarnings("deprecation")
    protected void doRegister() throws Exception {
        if (!listening) {
            listening = true;
            stream.setListener(new StreamListener());
        }
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException("a QUIC bridge channel is already connected");
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        open = false;
        releaseHeld();
        stream.close();
    }

    /**
     * Netty's read model, enforced exactly: with auto-read off nothing reaches the pipeline until
     * {@code read()}. Data the stream delivers anyway is held here; the stream is paused too, so
     * QUIC flow control bounds how much that can be.
     */
    @Override
    protected void doBeginRead() throws Exception {
        if (!held.isEmpty()) {
            deliver(held.poll());
            return;
        }
        if (config.isAutoRead()) {
            stream.setAutoRead(true);
        } else {
            readRequested = true;
            stream.read();
        }
    }

    /** Event loop only. */
    private void onIncoming(ByteBuf data) {
        if (!isOpen()) {
            data.release();
            return;
        }
        if (held.isEmpty() && (config.isAutoRead() || readRequested)) {
            deliver(data);
        } else {
            held.add(data);
        }
    }

    private void deliver(ByteBuf data) {
        readRequested = false;
        pipeline().fireChannelRead(data);
        pipeline().fireChannelReadComplete();
    }

    @Override
    protected void doDeregister() throws Exception {
        releaseHeld();
    }

    private void releaseHeld() {
        ByteBuf b;
        while ((b = held.poll()) != null) {
            b.release();
        }
    }

    /**
     * Hands flushed bytes to the stream, but only while the stream is writable. Otherwise the
     * rest stays in the outbound buffer, so the channel turns unwritable like a slow TCP socket,
     * and goes out once the stream drains.
     */
    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        boolean wrote = false;
        while (true) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            if (!stream.isWritable()) {
                break; // resumed by StreamListener.onWritabilityChanged
            }
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isReadable()) {
                stream.write(buf.nioBuffer());
                wrote = true;
            }
            in.remove();
        }
        if (wrote) {
            stream.flush();
        }
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            return msg;
        }
        throw new UnsupportedOperationException("unsupported message type: " + msg.getClass().getName());
    }

    private final class BridgeUnsafe extends AbstractUnsafe {
        /**
         * The QUIC stream is connected before this channel exists, so connect() succeeds at once
         * while the channel is open; the address is not used. That lets a client that connects
         * through a {@code Bootstrap} (vanilla's client, MCProtocolLib) take this channel from a
         * channel factory unchanged. channelActive has already fired at registration.
         */
        @Override
        public void connect(SocketAddress remote, SocketAddress local, ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }
            if (isActive()) {
                promise.setSuccess();
            } else {
                promise.setFailure(new java.nio.channels.ClosedChannelException());
            }
        }
    }

    /**
     * Called on the stream's thread. In the hosted transport that is this channel's own loop and
     * everything runs right here; otherwise it is forwarded to this channel's event loop.
     */
    private final class StreamListener implements QuicByteStream.Listener {
        @Override
        public void onData(ByteBuffer data) {
            if (!open) {
                return;
            }
            ByteBuf copy = alloc().buffer(data.remaining());
            copy.writeBytes(data);
            run(() -> onIncoming(copy));
        }

        @Override
        public void onWritabilityChanged(boolean writable) {
            if (writable) {
                // Resume writing what doWrite left in the outbound buffer.
                run(() -> unsafe().flush());
            }
        }

        @Override
        public void onClosed(Throwable cause) {
            run(() -> {
                if (cause != null && isOpen()) {
                    pipeline().fireExceptionCaught(cause);
                }
                if (isOpen()) {
                    unsafe().close(voidPromise());
                }
            });
        }

        private void run(Runnable task) {
            EventLoop loop = eventLoop();
            if (loop.inEventLoop()) {
                task.run();
            } else {
                loop.execute(task);
            }
        }
    }

    /** Accepts TCP options (TCP_NODELAY, SO_KEEPALIVE, IP_TOS, …) and ignores them. */
    private final class Config extends DefaultChannelConfig {
        Config(Channel channel) {
            super(channel);
        }

        /**
         * Socket options that a game or proxy sets on its TCP channels. They have no meaning on a
         * QUIC stream, so they are accepted and ignored (returning false would make Bootstrap log
         * an "Unknown channel option" warning for every connection).
         */
        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            try {
                if (super.setOption(option, value)) {
                    return true;
                }
            } catch (RuntimeException e) {
                return false;
            }
            return option == ChannelOption.TCP_NODELAY || option == ChannelOption.IP_TOS
                    || option == ChannelOption.SO_KEEPALIVE || option == ChannelOption.SO_SNDBUF
                    || option == ChannelOption.SO_RCVBUF || option == ChannelOption.SO_LINGER
                    || option == ChannelOption.SO_REUSEADDR;
        }

        @Override
        protected void autoReadCleared() {
            stream.setAutoRead(false);
        }
    }
}
