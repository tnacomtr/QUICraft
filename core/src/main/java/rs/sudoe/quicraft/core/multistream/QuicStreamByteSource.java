// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * The receive side of an N+2 session: a {@link SessionReadPump.ByteSource} over Netty QUIC stream channels
 * (protocol.md 13.12).
 *
 * <p>Each attached channel runs with autoRead off. Netty delivers bytes only when {@link QuicStreamChannel#read()}
 * is called, and QUIC returns flow-control credit only when quiche reads. So the source calls {@code read()} only
 * while the grant the pump asked for leaves room: the buffered bytes of a stream never exceed the largest grant
 * passed to {@link #read(int, byte[], int, int)}, and once they reach it no read is issued and the peer gets no
 * new credit. The per-read allocation size is set from that room through {@link ReadGrantAllocator}.
 *
 * <p>Netty reports a FIN as {@link ChannelInputShutdownReadComplete}, after all the peer's data. The source then
 * returns the bytes still buffered and afterwards 0 forever, with no further read calls. A stream reset (any
 * exception on the channel, or the channel going inactive before a FIN) releases the buffered bytes at once, and
 * every later {@link #read} on that stream throws {@link IOException}. The source consumes the exception so it
 * is not logged as unhandled; the owning session sees it as the IOException.
 *
 * <p>Event-loop confined. {@link #attach}, {@link #read} and {@link #close} must run on the event loop of the
 * connection that owns the channels, the same loop Netty uses to deliver {@code channelRead}. The read pump calls
 * this class from that loop. Attach a channel before the first read on it; the channel's receive allocator is
 * installed by {@link ReadGrantAllocator#install}, which throws if QUIC already cached a different handle.
 */
public final class QuicStreamByteSource implements SessionReadPump.ByteSource {
    private final Lane[] lanes;
    private boolean closed;

    /** One logical stream: its channel, its buffered ByteBufs in arrival order, and its end state. */
    private static final class Lane {
        final QuicStreamChannel channel;
        final ArrayDeque<ByteBuf> queue = new ArrayDeque<ByteBuf>();
        int buffered;
        boolean eof;
        boolean reset;
        int readCalls;

        Lane(QuicStreamChannel channel) {
            this.channel = channel;
        }

        void releaseAll() {
            for (ByteBuf buf : queue) {
                buf.release();
            }
            queue.clear();
            buffered = 0;
        }
    }

    /** Feeds one lane's channel events into its lane. */
    private static final class Feed extends ChannelInboundHandlerAdapter {
        private final QuicStreamByteSource source;
        private final Lane lane;

        Feed(QuicStreamByteSource source, Lane lane) {
            this.source = source;
            this.lane = lane;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf) || source.closed || lane.reset) {
                ReferenceCountUtil.release(msg);
                return;
            }
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                buf.release();
                return;
            }
            lane.queue.add(buf);
            lane.buffered += buf.readableBytes();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof ChannelInputShutdownReadComplete) {
                lane.eof = true;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            markReset(lane);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!lane.eof) {
                markReset(lane);
            }
            ctx.fireChannelInactive();
        }
    }

    /**
     * Creates a source for {@code streams} logical streams (N+2), none attached yet.
     *
     * @param streams the number of logical streams; indexes run from 0 to {@code streams - 1}
     */
    public QuicStreamByteSource(int streams) {
        if (streams <= 0) {
            throw new IllegalArgumentException("streams " + streams);
        }
        this.lanes = new Lane[streams];
    }

    /**
     * Attaches the peer-created channel of logical stream {@code stream}. Sets autoRead off and installs the
     * grant allocator, so no data arrives until {@link #read} asks for it.
     *
     * @throws IllegalStateException when the index is already attached or the allocator cannot be installed
     */
    public void attach(int stream, QuicStreamChannel channel) {
        checkIndex(stream);
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (lanes[stream] != null) {
            throw new IllegalStateException("stream " + stream + " is already attached");
        }
        channel.config().setAutoRead(false);
        ReadGrantAllocator.install(channel);
        Lane lane = new Lane(channel);
        lanes[stream] = lane;
        channel.pipeline().addLast(new Feed(this, lane));
    }

    /**
     * Copies up to {@code max} buffered bytes of {@code stream} into {@code into} at {@code offset}, then issues at
     * most one channel read if the stream's buffer has room under {@code max}.
     *
     * @return the count copied, 0 when none are buffered (including after FIN) or after {@link #close}; never more
     *     than {@code max}
     * @throws IOException when the stream was reset
     */
    @Override
    public int read(int stream, byte[] into, int offset, int max) throws IOException {
        Lane lane = lane(stream);
        if (lane.reset) {
            throw new IOException("serverbound stream " + stream + " was reset");
        }
        if (into == null || offset < 0 || max < 0 || offset > into.length - max) {
            throw new IllegalArgumentException("read window");
        }
        if (closed || max == 0) {
            return 0;
        }
        int copied = 0;
        while (copied < max && !lane.queue.isEmpty()) {
            ByteBuf head = lane.queue.peek();
            int take = Math.min(head.readableBytes(), max - copied);
            head.readBytes(into, offset + copied, take);
            copied += take;
            lane.buffered -= take;
            if (!head.isReadable()) {
                lane.queue.poll();
                head.release();
            }
        }
        int room = max - lane.buffered;
        if (!lane.eof && room > 0 && lane.channel.isOpen()) {
            ReadGrantAllocator.limit(lane.channel, Math.min(room, ServerReadPartition.MAX_READ_GRANT));
            lane.readCalls++;
            lane.channel.read();
        }
        return copied;
    }

    /**
     * Ends the source: releases every buffered ByteBuf and makes later reads return 0. Idempotent. The channels
     * stay open; their owner closes the connection.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Lane lane : lanes) {
            if (lane != null) {
                lane.releaseAll();
            }
        }
    }

    /** Bytes buffered for {@code stream} and not yet copied out. */
    int buffered(int stream) {
        return lane(stream).buffered;
    }

    /** Number of channel read calls issued for {@code stream}. */
    int readCalls(int stream) {
        return lane(stream).readCalls;
    }

    /** The head ByteBuf still buffered for {@code stream}, or null; for release checks in tests. */
    ByteBuf headForTest(int stream) {
        return lane(stream).queue.peek();
    }

    private static void markReset(Lane lane) {
        if (lane.reset) {
            return;
        }
        lane.reset = true;
        lane.releaseAll();
    }

    private Lane lane(int stream) {
        checkIndex(stream);
        Lane lane = lanes[stream];
        if (lane == null) {
            throw new IllegalStateException("stream " + stream + " is not attached");
        }
        return lane;
    }

    private void checkIndex(int stream) {
        if (stream < 0 || stream >= lanes.length) {
            throw new IllegalArgumentException("stream " + stream);
        }
    }
}
