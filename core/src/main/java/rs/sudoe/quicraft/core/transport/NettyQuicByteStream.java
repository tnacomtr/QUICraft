// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import rs.sudoe.quicraft.core.Protocol;

/**
 * {@link QuicByteStream} over a Netty {@link QuicStreamChannel}.
 *
 * <p>Writability is tracked here: Netty's QUIC stream doesn't count writes queued from other
 * threads, so on its own it reports writable while megabytes pile up behind a slow peer. A
 * stream is unwritable while more than {@link #HIGH_WATER} bytes are in flight (written but not
 * yet accepted by quiche) and becomes writable again below {@link #LOW_WATER}.
 */
final class NettyQuicByteStream extends ChannelInboundHandlerAdapter implements QuicByteStream {
    private static boolean COALESCE = Boolean.parseBoolean(
            System.getProperty("quicraft.coalesceWrites", "false"));
    private static int COALESCE_TARGET = 1200;

    static final long HIGH_WATER = 1L << 20;
    static final long LOW_WATER = 256L << 10;

    private final QuicStreamChannel channel;
    private final java.util.concurrent.atomic.AtomicLong inFlight = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean throttled;
    private final AtomicBoolean closedNotified = new AtomicBoolean();
    /** Hard close after this long if the peer never answers our FIN. */
    static final long LINGER_MILLIS = 10_000;
    private volatile boolean closeRequested;
    /** Event loop only: the peer's FIN arrived. */
    private boolean inputShutdown;
    /** Event loop only: our FIN is queued. */
    private boolean outputShutdown;
    /** Event loop only: close() was called; FIN goes out once every write is in quiche. */
    private boolean finWhenWritten;
    /** Something was written: a close must not drop it. */
    private volatile boolean wroteAny;
    private volatile Listener listener;

    /** Coalescing state: pending bytes, their entry records, and whether a drain is scheduled. */
    private ByteBuf pendingBuffer;
    private java.util.List<WriteEntry> pendingEntries;
    private boolean drainScheduled;

    private static final class WriteEntry {
        final int length;
        final java.util.concurrent.CompletableFuture<Void> promise = new java.util.concurrent.CompletableFuture<>();
        WriteEntry(int length) {
            this.length = length;
        }
    }

    NettyQuicByteStream(QuicStreamChannel channel) {
        this.channel = channel;
        channel.config().setAutoRead(false);
        channel.pipeline().addLast(this);
    }

    io.netty.handler.codec.quic.QuicChannel connection() {
        return channel.parent();
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
        int length = data.remaining();
        if (!COALESCE) {
            ByteBuf copy = channel.alloc().buffer(length);
            copy.writeBytes(data.duplicate());
            inFlight.addAndGet(length);
            wroteAny = true;
            channel.write(copy).addListener(f -> {
                long left = inFlight.addAndGet(-length);
                if (!f.isSuccess()) {
                    channel.pipeline().fireExceptionCaught(f.cause());
                }
                if (left == 0 && finWhenWritten) {
                    sendFin();
                }
                if (throttled && left <= LOW_WATER) {
                    throttled = false;
                    notifyWritable();
                }
            });
            return;
        }

        // Coalescing path: bounded buffer (~QUIC max payload), never reorder.
        boolean directPass = length >= COALESCE_TARGET;
        if (directPass) {
            // Large write passes through directly after draining any pending buffer.
            if (pendingBuffer != null && pendingBuffer.readableBytes() > 0) {
                drainPending();
            }
            ByteBuf copy = channel.alloc().buffer(length);
            copy.writeBytes(data.duplicate());
            inFlight.addAndGet(length);
            wroteAny = true;
            channel.write(copy).addListener(f -> {
                long left = inFlight.addAndGet(-length);
                if (!f.isSuccess()) {
                    channel.pipeline().fireExceptionCaught(f.cause());
                }
                if (left == 0 && finWhenWritten) {
                    sendFin();
                }
                if (throttled && left <= LOW_WATER) {
                    throttled = false;
                    notifyWritable();
                }
            });
            return;
        }

        // Small write: append if room, else drain pending first then append.
        if (pendingBuffer != null && pendingBuffer.readableBytes() > 0) {
            if (pendingBuffer.readableBytes() + length > COALESCE_TARGET) {
                drainPending();
                pendingBuffer = channel.alloc().buffer();
                pendingEntries = new java.util.ArrayList<>();
                pendingBuffer.writeBytes(data.duplicate());
                pendingEntries.add(new WriteEntry(length));
                inFlight.addAndGet(length);
                wroteAny = true;
                scheduleDrain();
                return;
            }
            pendingBuffer.writeBytes(data.duplicate());
            pendingEntries.add(new WriteEntry(length));
            inFlight.addAndGet(length);
            wroteAny = true;
            scheduleDrain();
        } else {
            pendingBuffer = channel.alloc().buffer();
            pendingEntries = new java.util.ArrayList<>();
            pendingBuffer.writeBytes(data.duplicate());
            pendingEntries.add(new WriteEntry(length));
            inFlight.addAndGet(length);
            wroteAny = true;
            scheduleDrain();
        }
    }

    @Override
    public void flush() {
        if (COALESCE && pendingBuffer != null && pendingBuffer.readableBytes() > 0) {
            if (!drainScheduled) {
                drainScheduled = true;
                channel.eventLoop().execute(this::drainPending);
            }
        } else {
            channel.flush();
        }
    }

    private void scheduleDrain() {
        if (!drainScheduled) {
            drainScheduled = true;
            channel.eventLoop().execute(this::drainPending);
        }
    }

    private void drainPending() {
        drainScheduled = false;
        if (pendingBuffer == null || pendingBuffer.readableBytes() == 0) {
            return;
        }
        ByteBuf coalesced = pendingBuffer;
        pendingBuffer = null;
        java.util.List<WriteEntry> entries = pendingEntries;
        pendingEntries = null;

        int totalLen = coalesced.readableBytes();
        // inFlight was incremented when bytes were appended.
        channel.write(coalesced).addListener(f -> {
            long left = inFlight.addAndGet(-totalLen);
            for (WriteEntry entry : entries) {
                if (f.isSuccess()) {
                    entry.promise.complete(null);
                } else {
                    entry.promise.completeExceptionally(f.cause());
                }
            }
            if (!f.isSuccess()) {
                channel.pipeline().fireExceptionCaught(f.cause());
            }
            if (left == 0 && finWhenWritten) {
                sendFin();
            }
            if (throttled && left <= LOW_WATER) {
                throttled = false;
                notifyWritable();
            }
        });
    }

    @Override
    public boolean isWritable() {
        if (inFlight.get() < HIGH_WATER) {
            return channel.isWritable();
        }
        throttled = true;
        // A write may have completed between the check and setting the flag; don't miss it.
        if (inFlight.get() <= LOW_WATER) {
            throttled = false;
            return channel.isWritable();
        }
        return false;
    }

    long inFlightForTest() {
        return inFlight.get();
    }

    private void notifyWritable() {
        Listener l = listener;
        if (l != null) {
            l.onWritabilityChanged(true);
        }
    }

    @Override
    public void setAutoRead(boolean autoRead) {
        channel.config().setAutoRead(autoRead);
    }

    @Override
    public void read() {
        channel.read();
    }

    /** For a close that bypasses {@link #close()}: isOpen() is false from now on. */
    void markClosing() {
        closeRequested = true;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen() && !closedNotified.get() && !closeRequested;
    }

    /**
     * Closes like TCP (docs/protocol.md §8): what was written goes out first, followed by FIN,
     * and the peer closes the connection once it has read everything. Closing the QUIC
     * connection right away would drop data still unsent or unacknowledged, e.g. a disconnect
     * message. If the peer never answers, the connection is closed after {@link #LINGER_MILLIS}.
     * If the peer's FIN already arrived, or nothing was ever written, the connection closes now.
     */
    @Override
    public void close() {
        closeRequested = true;
        // Always a task, even on the loop: see Codecs.closeLater.
        channel.eventLoop().execute(this::close0);
    }

    private void close0() {
        if (!channel.parent().isOpen()) {
            return;
        }
        // Deliver any coalesced pending data before deciding to close.
        if (COALESCE && pendingBuffer != null && pendingBuffer.readableBytes() > 0) {
            ByteBuf coalesced = pendingBuffer;
            pendingBuffer = null;
            java.util.List<WriteEntry> entries = pendingEntries;
            pendingEntries = null;
            drainScheduled = false;
            int totalLen = coalesced.readableBytes();
            channel.write(coalesced).addListener(f -> {
                long left = inFlight.addAndGet(-totalLen);
                for (WriteEntry entry : entries) {
                    if (f.isSuccess()) {
                        entry.promise.complete(null);
                    } else {
                        entry.promise.completeExceptionally(f.cause());
                    }
                }
                if (!f.isSuccess()) {
                    channel.pipeline().fireExceptionCaught(f.cause());
                }
                if (left == 0 && finWhenWritten) {
                    sendFin();
                }
                if (throttled && left <= LOW_WATER) {
                    throttled = false;
                    notifyWritable();
                }
            });
        }
        if (inputShutdown || !wroteAny || !channel.isActive()) {
            // Nothing of ours to deliver (or the peer is already done): close now.
            closeConnection();
            return;
        }
        if (finWhenWritten) {
            return;
        }
        finWhenWritten = true;
        channel.flush();
        channel.eventLoop().schedule(this::closeConnection, LINGER_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (inFlight.get() == 0) {
            sendFin();
        }
        // Otherwise the last write's listener sends it: FIN before queued data would drop it.
    }

    /** Event loop only. */
    private void sendFin() {
        if (outputShutdown || !channel.isActive()) {
            return;
        }
        outputShutdown = true;
        channel.shutdownOutput().addListener(f -> {
            if (!f.isSuccess()) {
                closeConnection();
            }
        });
    }

    private void closeConnection() {
        Codecs.closeLater(channel.parent(), true, Protocol.CLOSE_NORMAL);
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.Map<String, Long>> connectionStats() {
        java.util.concurrent.CompletableFuture<java.util.Map<String, Long>> result =
                new java.util.concurrent.CompletableFuture<>();
        channel.parent().collectStats().addListener(f -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(f.cause());
                return;
            }
            io.netty.handler.codec.quic.QuicConnectionStats st =
                    (io.netty.handler.codec.quic.QuicConnectionStats) f.getNow();
            java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
            m.put("sent", st.sent());
            m.put("recv", st.recv());
            m.put("lost", st.lost());
            m.put("retrans", st.retrans());
            m.put("sentBytes", st.sentBytes());
            m.put("recvBytes", st.recvBytes());
            m.put("lostBytes", st.lostBytes());
            m.put("streamRetransBytes", st.streamRetransBytes());
            result.complete(m);
        });
        return result;
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
            l.onWritabilityChanged(isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof ChannelInputShutdownReadComplete) {
            // Peer sent FIN, after all its data: like a TCP close (docs/protocol.md §8). Whoever
            // receives the FIN closes the connection.
            inputShutdown = true;
            notifyClosed(null);
            closeConnection();
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
        Codecs.closeLater(channel.parent(), true, Protocol.CLOSE_INTERNAL_ERROR);
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
