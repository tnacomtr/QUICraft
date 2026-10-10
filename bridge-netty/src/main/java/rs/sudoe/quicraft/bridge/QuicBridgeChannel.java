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
    /**
     * Pipeline delivery happens in slices of about this many bytes, one slice per event loop
     * pass, so the loop reads its sockets between slices. Without that, a game that decodes a
     * chunk burst inline would leave the UDP socket unread long enough to overflow it (UDP has no
     * flow control; TCP's kernel buffer absorbs the same burst).
     */
    static final int SLICE_BYTES = 64 * 1024;
    /**
     * Up to this many bytes per event loop pass go to the pipeline right away, inside the read,
     * so a reply goes out in the same packet as QUIC's ACK (deferring every read cost an extra
     * ACK-only packet per round trip). Only beyond it, i.e. in a burst, delivery is sliced.
     */
    static final int INLINE_BYTES_PER_PASS = 32 * 1024;
    /** Above this many bytes waiting, the stream is paused, so QUIC flow control stops the peer. */
    static final long HELD_HIGH = 4L << 20;
    static final long HELD_LOW = 1L << 20;

    /** Event loop only: data received but not yet delivered to the pipeline. */
    private final java.util.ArrayDeque<ByteBuf> held = new java.util.ArrayDeque<>();
    /** Event loop only: bytes in {@link #held}. */
    private long heldBytes;
    /** Event loop only: the stream is paused because too much is held. */
    private boolean pausedForHeld;
    /** Event loop only: a slice task is queued or running, or an inline delivery is running. */
    private boolean delivering;
    /** Event loop only: what may still be delivered inline in this loop pass. */
    private long inlineBudget = INLINE_BYTES_PER_PASS;
    /** Event loop only: the task that refills {@link #inlineBudget} after this pass is queued. */
    private boolean budgetResetQueued;
    private final Runnable resetInlineBudget = () -> {
        budgetResetQueued = false;
        inlineBudget = INLINE_BYTES_PER_PASS;
    };
    /** Event loop only: the stream closed; close the channel once held data is delivered. */
    private boolean closeWhenDrained;
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

    /**
     * Open and registered. Deliberately not tied to {@code stream.isOpen()}: the stream closing
     * reaches this channel as a close, which must fire channelInactive (the game's disconnect
     * handling) after any data still being delivered.
     */
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
            if (!stream.isOpen()) {
                // Closed before we listened: the listener will never hear of it.
                eventLoop().execute(() -> unsafe().close(voidPromise()));
            }
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
        if (!config.isAutoRead()) {
            readRequested = true;
        }
        if (!held.isEmpty()) {
            scheduleDelivery();
            return;
        }
        if (config.isAutoRead()) {
            if (!pausedForHeld) {
                stream.setAutoRead(true);
            }
        } else {
            stream.read();
        }
    }

    /** Event loop only. */
    private void onIncoming(ByteBuf data) {
        if (!isOpen()) {
            data.release();
            return;
        }
        held.add(data);
        heldBytes += data.readableBytes();
        if (heldBytes > HELD_HIGH && !pausedForHeld) {
            pausedForHeld = true;
            stream.setAutoRead(false);
        }
        if (!delivering && inlineBudget > 0 && canDeliver() && eventLoop().inEventLoop()) {
            if (!budgetResetQueued) {
                budgetResetQueued = true;
                eventLoop().execute(resetInlineBudget);
            }
            delivering = true;
            inlineBudget -= deliverUpTo(inlineBudget);
            delivering = false;
            afterDelivery();
            if (held.isEmpty()) {
                return;
            }
        }
        scheduleDelivery();
    }

    /** Delivers held data up to about {@code budget} bytes; returns the bytes delivered. */
    private long deliverUpTo(long budget) {
        long delivered = 0;
        ByteBuf data;
        while (delivered < budget && canDeliver() && (data = held.poll()) != null) {
            int n = data.readableBytes();
            heldBytes -= n;
            delivered += n;
            readRequested = false;
            pipeline().fireChannelRead(data);
        }
        if (delivered > 0) {
            // May call read() (auto-read): doBeginRead sees delivering and leaves the rest to us.
            pipeline().fireChannelReadComplete();
        }
        return delivered;
    }

    /** Resumes a stream paused for held data, and completes a close that waited for it. */
    private void afterDelivery() {
        if (pausedForHeld && heldBytes < HELD_LOW) {
            pausedForHeld = false;
            if (config.isAutoRead()) {
                stream.setAutoRead(true);
            } else if (readRequested) {
                stream.read();
            }
        }
        if (held.isEmpty() && closeWhenDrained && isOpen() && !delivering) {
            unsafe().close(voidPromise());
        }
    }

    /** Event loop only. */
    long heldBytesForTest() {
        return heldBytes;
    }

    private boolean canDeliver() {
        return config.isAutoRead() || readRequested;
    }

    /** The first slice runs later in this loop pass; see {@link #SLICE_BYTES}. */
    private void scheduleDelivery() {
        if (!delivering && !held.isEmpty() && canDeliver()) {
            delivering = true;
            eventLoop().execute(this::deliverSlice);
        }
    }

    private void deliverSlice() {
        if (!isOpen()) {
            delivering = false;
            releaseHeld();
            return;
        }
        deliverUpTo(SLICE_BYTES);
        if (!held.isEmpty() && canDeliver() && isOpen()) {
            // Next pass: the loop reads its sockets first.
            eventLoop().schedule(this::deliverSlice, 0, java.util.concurrent.TimeUnit.NANOSECONDS);
            afterDelivery();
            return;
        }
        delivering = false;
        afterDelivery();
    }

    @Override
    protected void doDeregister() throws Exception {
        releaseHeld();
    }

    private void releaseHeld() {
        heldBytes = 0;
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
                // Resume writing what doWrite left in the outbound buffer. Always a task, even on
                // this loop: this can fire inside our own flush, where a nested flush is ignored.
                eventLoop().execute(() -> unsafe().flush());
            }
        }

        @Override
        public void onClosed(Throwable cause) {
            run(() -> {
                if (cause != null && isOpen()) {
                    pipeline().fireExceptionCaught(cause);
                }
                if (!isOpen()) {
                    return;
                }
                if (cause == null && !held.isEmpty()) {
                    // Like TCP: what the peer sent before closing (e.g. a disconnect message)
                    // reaches the pipeline first, if the game is reading.
                    closeWhenDrained = true;
                    if (!delivering && !canDeliver()) {
                        unsafe().close(voidPromise());
                    }
                    return;
                }
                unsafe().close(voidPromise());
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
