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
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletableFuture;
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
    /** Event loop only: the stream was handed to a lane session, so this channel never touches it. */
    private boolean handedOver;
    /** Event loop only: a {@link #handOver} waiting for its ordering guarantee. */
    private PendingHandOver pendingHandOver;
    /** Event loop only: the zero-byte marker write of a {@link #handOver}; see {@link #doWrite}. */
    private ByteBuf handOverMarker;

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
        if (pendingHandOver != null) {
            // Nothing will drain now, so the caller must be told, and the leftover it would have
            // owned is released here.
            failHandOver(new ClosedChannelException());
        }
        releaseHeld();
        if (!handedOver) {
            stream.close();
        }
        // After handOver the lane session alone ends stream 0 (13.14): closing this channel is the
        // game's pipeline going away, not a FIN or CONNECTION_CLOSE 0x0 on a stream we no longer own.
    }

    /**
     * Netty's read model, enforced exactly: with auto-read off nothing reaches the pipeline until
     * {@code read()}. Data the stream delivers anyway is held here; the stream is paused too, so
     * QUIC flow control bounds how much that can be.
     */
    @Override
    protected void doBeginRead() throws Exception {
        if (handedOver) {
            // The lane session reads stream 0 now, not this channel (12.10).
            return;
        }
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
        if (handedOver) {
            // The lane session owns stream 0. A byte arriving here means the caller handed the
            // stream over without stopping it first (12.10): dropping it would silently corrupt the
            // record stream, so this fails closed instead.
            data.release();
            IllegalStateException tooLate = new IllegalStateException(
                    "stream 0 delivered data after it was handed to the lane session; stop it first"
                            + " (12.10, 12.13)");
            pipeline().fireExceptionCaught(tooLate);
            unsafe().close(voidPromise());
            return;
        }
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
        if (handedOver) {
            // The stream belongs to the lane session: never resume reading it.
            return;
        }
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

    /**
     * The {@code /3} cutover of docs/protocol.md 12.11/12.12, 13.2 and 13.14: hands stream 0 to a
     * {@link rs.sudoe.quicraft.core.multistream.LaneSession}, so the lane session becomes its only
     * owner and this channel stops being the game's transport.
     *
     * <p>Call it on this channel's event loop, at the byte boundary of the peer's final switch
     * packet, after this side's own switch packet was written to the pipeline. It does four
     * things:</p>
     *
     * <ul>
     *   <li><b>Ordering.</b> The returned future completes only once every write submitted before
     *       this call has been handed to the stream, in order, and can never complete earlier. A
     *       message still queued because the stream was not writable therefore holds the future
     *       back until it drains, instead of letting a record overtake the switch packet that was
     *       queued first. It completes exceptionally when the channel closes first, and the
     *       {@code controlLeftover} buffer is released in that case, so the caller must not release
     *       it when the future fails.</li>
     *   <li><b>No more reading.</b> The stream stops delivering (auto-read off) and nothing already
     *       held here reaches the pipeline, so no byte the lane session owns is consumed twice.</li>
     *   <li><b>The bytes already read.</b> Everything this channel read from the stream but the game
     *       pipeline has not consumed, plus what the caller passes in {@code frameCumulation}, comes
     *       back as one {@code controlLeftover} buffer that the caller owns and must release. The
     *       bytes are in wire order: {@code frameCumulation} was read from the stream before the
     *       bytes still held here (12.13). A ByteBuf the caller hands in is copied, not consumed:
     *       the caller keeps ownership of its own buffer.</li>
     *   <li><b>Detachment.</b> Closing this channel no longer closes the stream, so the lane
     *       session alone ends stream 0: no FIN and no {@code CONNECTION_CLOSE} {@code 0x0} on a
     *       stream it owns (13.14, plan gap G15). Later writes submitted to this channel fail
     *       rather than reach the stream behind the session's back.</li>
     * </ul>
     *
     * @param frameCumulation what the caller still holds undecoded, typically the frame decoder's
     *     own cumulation; its bytes are copied and it stays the caller's. Null means none
     * @return a future completed with the {@code controlLeftover} buffer (refCnt 1, caller-owned),
     *     already complete when nothing was queued
     * @throws IllegalStateException off the event loop, or on a second call
     */
    public CompletableFuture<ByteBuf> handOver(ByteBuf frameCumulation) {
        if (!eventLoop().inEventLoop()) {
            throw new IllegalStateException("handOver must run on the channel's event loop (12.10)");
        }
        if (handedOver) {
            throw new IllegalStateException("stream 0 was already handed over");
        }
        handedOver = true;
        // (b) Nothing more is read from stream 0, and nothing already held is delivered: from here
        // on those bytes belong to the lane session, not to the game pipeline.
        stream.setAutoRead(false);
        readRequested = false;
        // (c) One buffer, in wire order: what the caller still holds undecoded first, then what this
        // channel read but has not delivered (12.13).
        int cumulative = frameCumulation == null ? 0 : frameCumulation.readableBytes();
        ByteBuf leftover = alloc().buffer(cumulative + (int) Math.min(heldBytes, Integer.MAX_VALUE));
        try {
            if (cumulative > 0) {
                leftover.writeBytes(frameCumulation, frameCumulation.readerIndex(), cumulative);
            }
            ByteBuf heldBuffer;
            while ((heldBuffer = held.poll()) != null) {
                // A polled buffer that is not handed to the leftover or released here would be owned
                // by nobody: the queue no longer holds it. Every path through this loop therefore
                // ends with it released.
                try {
                    leftover.writeBytes(heldBuffer);
                } finally {
                    heldBuffer.release();
                }
            }
        } catch (Throwable t) {
            // An allocation or copy failure leaves whatever is still queued and the leftover itself
            // to release, and the caller must not get a partially built buffer.
            releaseHeld();
            leftover.release();
            throw t;
        }
        heldBytes = 0;
        PendingHandOver pending = new PendingHandOver(leftover);
        pendingHandOver = pending;
        // (d) A zero-byte write appended after every write submitted before it. It carries the
        // ordering guarantee: doWrite walks the outbound buffer in order, so this promise can only
        // succeed once everything queued earlier reached the stream. It also makes the unwritable
        // case observable: the marker stays queued with the rest until the stream drains.
        ChannelPromise marker = newPromise();
        marker.addListener(future -> {
            if (future.isSuccess()) {
                completeHandOver();
            } else {
                failHandOver(future.cause());
            }
        });
        if (!isOpen()) {
            marker.tryFailure(new ClosedChannelException());
            return pending.result;
        }
        handOverMarker = alloc().buffer(0);
        unsafe().write(handOverMarker, marker);
        unsafe().flush();
        return pending.result;
    }

    /** Event loop only: the marker reached the stream's write path, so every earlier write did too. */
    private void completeHandOver() {
        PendingHandOver pending = pendingHandOver;
        if (pending == null) {
            return;
        }
        pendingHandOver = null;
        if (!pending.result.complete(pending.leftover)) {
            // The caller gave up on the future, so nobody will own the leftover.
            pending.leftover.release();
        }
    }

    /** Event loop only: the hand-over cannot be honoured, so the caller gets nothing to own. */
    private void failHandOver(Throwable cause) {
        PendingHandOver pending = pendingHandOver;
        if (pending == null) {
            return;
        }
        pendingHandOver = null;
        pending.leftover.release();
        pending.result.completeExceptionally(cause != null ? cause : new ClosedChannelException());
    }

    /** One {@link #handOver} that is still waiting for the writes queued before it. */
    private static final class PendingHandOver {
        final CompletableFuture<ByteBuf> result = new CompletableFuture<>();
        final ByteBuf leftover;

        PendingHandOver(ByteBuf leftover) {
            this.leftover = leftover;
        }
    }

    private boolean canDeliver() {
        return !handedOver && (config.isAutoRead() || readRequested);
    }

    /** The first slice runs later in this loop pass; see {@link #SLICE_BYTES}. */
    private void scheduleDelivery() {
        if (!delivering && !held.isEmpty() && canDeliver()) {
            delivering = true;
            eventLoop().execute(this::deliverSlice);
        }
    }

    private void deliverSlice() {
        if (handedOver) {
            delivering = false;
            return;
        }
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
            if (msg == handOverMarker) {
                // The marker of handOver (see there): every write queued before it was handed to the
                // stream in this same pass, so its promise completes the hand-over now.
                in.remove();
                handOverMarker = null;
                continue;
            }
            if (handedOver && handOverMarker == null) {
                // Submitted after the hand-over: the lane session owns stream 0, so a raw byte here
                // would corrupt the record stream (13.4).
                in.remove(new IllegalStateException(
                        "stream 0 was handed to the lane session; writes go through it now"));
                continue;
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
