// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.util.function.IntPredicate;

/**
 * The send side of an N+2 session: drains {@link SessionSendDriver#poll} into the Netty QUIC stream channels
 * (protocol.md 13.12).
 *
 * <p>A stream is writable when its channel is active and {@link QuicStreamChannel#isWritable()}. Each slice
 * is written with {@code writeAndFlush}, and its outcome is reported to the driver from the write future: success
 * calls {@link SessionSendDriver#released(long)}, failure calls {@link SessionSendDriver#failed(long)}. The record's
 * outbound charge is therefore held until the write future completes, not when the slice is queued.
 *
 * <p>The write future is the release point. Netty QUIC exposes nothing closer to the send-buffer release, so the
 * charge can be held a little longer than the bytes sit in the send buffer. It is never released earlier.
 *
 * <p>The sink re-polls the driver whenever a channel's writability changes, so a stream that was blocked is
 * drained again as soon as it can take bytes.
 *
 * <p>Event-loop confined. {@link #attach} and {@link #drain} run on the channels' event loop, and the writability
 * re-poll runs there too. The driver's methods are synchronized, so the sink never holds a driver lock while it
 * writes.
 */
public final class QuicStreamWriteSink {
    private final SessionSendDriver driver;
    private final QuicStreamChannel[] channels;
    private final IntPredicate writable = this::isWritable;
    private final IntPredicate gate;
    private final Settled settled;
    /** Test hook: runs on the loop after each poll returned a slice and before that slice is written. */
    private volatile Runnable beforeWrite;

    /** Told after each slice's write future completed and the driver was told; on the event loop. */
    interface Settled {
        /** {@code success} is false when the write failed, for example after a peer STOP_SENDING. */
        void settled(boolean success);
    }

    /**
     * Creates a sink for {@code streams} logical streams (N+2), none attached yet.
     *
     * @param driver the connection's send driver
     * @param streams the number of logical streams; indexes run from 0 to {@code streams - 1}
     */
    public QuicStreamWriteSink(SessionSendDriver driver, int streams) {
        this(driver, streams, stream -> true, success -> { });
    }

    /**
     * A sink whose owner gates streams and hears every completed write. A release can grant later records,
     * so the owner drains again from {@code settled}; the gate lets it hold a stream's writes back.
     *
     * @param gate a stream is written only while this also answers true
     * @param settled told after each write future completed and the driver was told
     */
    QuicStreamWriteSink(SessionSendDriver driver, int streams, IntPredicate gate, Settled settled) {
        if (gate == null || settled == null) {
            throw new NullPointerException("gate/settled");
        }
        this.gate = gate;
        this.settled = settled;
        if (driver == null) {
            throw new NullPointerException("driver");
        }
        if (streams <= 0) {
            throw new IllegalArgumentException("streams " + streams);
        }
        this.driver = driver;
        this.channels = new QuicStreamChannel[streams];
    }

    /**
     * Attaches the channel of logical stream {@code stream} and installs its writability watch.
     *
     * @throws IllegalStateException when the index is already attached
     */
    public void attach(int stream, QuicStreamChannel channel) {
        checkIndex(stream);
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (channels[stream] != null) {
            throw new IllegalStateException("stream " + stream + " is already attached");
        }
        channels[stream] = channel;
        channel.pipeline().addLast(new WritabilityWatch(this));
    }

    /**
     * Writes every slice the driver grants on a writable stream, until none is left. Call it after new records
     * are submitted and whenever a stream becomes writable.
     */
    public void drain() {
        for (;;) {
            SessionSendDriver.Write write = driver.poll(writable);
            if (write == null) {
                return;
            }
            Runnable hook = beforeWrite;
            if (hook != null) {
                hook.run();
            }
            write(write);
        }
    }

    /**
     * Submits one slice to its channel. The driver learns the outcome from the write future. A slice for a stream
     * with no channel fails at once, so its charge is still released.
     */
    void write(SessionSendDriver.Write write) {
        long token = write.token();
        QuicStreamChannel channel = write.stream() >= 0 && write.stream() < channels.length
                ? channels[write.stream()] : null;
        if (channel == null) {
            driver.failed(token);
            return;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(write.bytes());
        channel.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
            boolean success = future.isSuccess();
            if (success) {
                driver.released(token);
            } else {
                driver.failed(token);
            }
            settled.settled(success);
        });
    }

    /** Installs or clears the poll-to-write test hook. */
    void beforeWriteForTest(Runnable hook) {
        beforeWrite = hook;
    }

    private boolean isWritable(int stream) {
        if (stream < 0 || stream >= channels.length) {
            return false;
        }
        QuicStreamChannel channel = channels[stream];
        return channel != null && channel.isActive() && channel.isWritable() && gate.test(stream);
    }

    private void checkIndex(int stream) {
        if (stream < 0 || stream >= channels.length) {
            throw new IllegalArgumentException("stream " + stream);
        }
    }

    /** Re-polls the sink when its channel's writability changes. */
    private static final class WritabilityWatch extends ChannelInboundHandlerAdapter {
        private final QuicStreamWriteSink sink;

        WritabilityWatch(QuicStreamWriteSink sink) {
            this.sink = sink;
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            sink.drain();
            ctx.fireChannelWritabilityChanged();
        }
    }
}
