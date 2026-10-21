// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * The send side of an N+3 session: drains {@link SessionSendDriver#poll} into the Netty QUIC stream channels
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
 *
 * <p>When the system property {@code quicraft.coalesceWrites} is {@code true}, the sink gathers slices per logical
 * stream during a single {@link #drain()} call and writes one combined buffer per stream, capped at 64 KiB per
 * stream. Otherwise each slice is written individually with the behavior unchanged.
 */
public final class QuicStreamWriteSink {
    private static final boolean COALESCE_DEFAULT = Boolean.parseBoolean(
            System.getProperty("quicraft.coalesceWrites", "false"));
    private static final int COALESCE_MAX_BYTES = 64 * 1024;

    private final SessionSendDriver driver;
    private final QuicStreamChannel[] channels;
    private final IntPredicate writable = this::isWritable;
    private final IntPredicate gate;
    private final Settled settled;
    /** Test hook: runs on the loop after each poll returned a slice and before that slice is written. */
    private volatile Runnable beforeWrite;
    private boolean coalesce;
    private final int[] gatheredBytes;
    private final byte[][] coalesceByteArrays;
    private final int[] coalesceByteCounts;
    private final long[][] coalesceTokenArrays;
    private final int[] coalesceTokenCounts;

    /** Told after each slice's write future completed and the driver was told; on the event loop. */
    interface Settled {
        /** {@code success} is false when the write failed, for example after a peer STOP_SENDING. */
        void settled(boolean success);
    }

    /**
     * Creates a sink for {@code streams} logical streams (N+3), none attached yet.
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
        this(driver, streams, gate, settled, COALESCE_DEFAULT);
    }

    QuicStreamWriteSink(SessionSendDriver driver, int streams, IntPredicate gate, Settled settled,
            boolean coalesce) {
        if (gate == null || settled == null) {
            throw new NullPointerException("gate/settled");
        }
        this.gate = gate;
        this.settled = settled;
        this.coalesce = coalesce;
        if (driver == null) {
            throw new NullPointerException("driver");
        }
        if (streams <= 0) {
            throw new IllegalArgumentException("streams " + streams);
        }
        this.driver = driver;
        this.channels = new QuicStreamChannel[streams];
        this.gatheredBytes = new int[streams];
        this.coalesceByteArrays = new byte[streams][];
        this.coalesceByteCounts = new int[streams];
        this.coalesceTokenArrays = new long[streams][];
        this.coalesceTokenCounts = new int[streams];
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
        traceWritability();
        if (!coalesce) {
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
        Arrays.fill(gatheredBytes, 0);
        Arrays.fill(coalesceByteCounts, 0);
        Arrays.fill(coalesceTokenCounts, 0);
        for (;;) {
            SessionSendDriver.Write write = driver.poll(writable);
            if (write == null) {
                flushCoalesced();
                return;
            }
            Runnable hook = beforeWrite;
            if (hook != null) {
                hook.run();
            }
            int stream = write.stream();
            QuicStreamChannel channel = stream >= 0 && stream < channels.length ? channels[stream] : null;
            if (channel == null) {
                driver.failed(write.token());
                continue;
            }
            byte[] bytes = write.bytes();
            if (driver.trace() != null) {
                driver.trace().written(stream, bytes.length, write.traceSelectedNanos,
                        write.traceStartedNanos, System.nanoTime());
            }
            gatherBytes(stream, bytes);
            gatherToken(stream, write.token());
            gatheredBytes[stream] += bytes.length;
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
        if (driver.trace() != null) {
            driver.trace().written(write.stream(), buf.readableBytes(), write.traceSelectedNanos,
                    write.traceStartedNanos, System.nanoTime());
        }
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

    void traceWritability() {
        if (driver.trace() == null) return;
        long now = System.nanoTime();
        for (int i = 0; i < channels.length; i++) {
            QuicStreamChannel channel = channels[i];
            if (channel != null) driver.trace().writable(i, channel.isActive(), channel.isWritable(),
                    channel.parent().isWritable(), gate.test(i), now);
        }
    }

    /** Installs or clears the poll-to-write test hook. */
    void beforeWriteForTest(Runnable hook) {
        beforeWrite = hook;
    }

    /** Package-private: forces coalescing on or off for tests, overriding the system property. */
    void setCoalesce(boolean coalesce) {
        this.coalesce = coalesce;
    }

    private boolean isWritable(int stream) {
        if (stream < 0 || stream >= channels.length) {
            return false;
        }
        if (coalesce && gatheredBytes[stream] >= COALESCE_MAX_BYTES) {
            return false;
        }
        QuicStreamChannel channel = channels[stream];
        return channel != null && channel.isActive() && channel.isWritable() && gate.test(stream);
    }

    private void gatherBytes(int stream, byte[] bytes) {
        byte[] arr = coalesceByteArrays[stream];
        int count = coalesceByteCounts[stream];
        int needed = count + bytes.length;
        if (arr == null || arr.length < needed) {
            int newSize = arr == null ? Math.max(bytes.length, 4) : Math.max(arr.length * 2, needed);
            byte[] newArr = new byte[newSize];
            if (arr != null) {
                System.arraycopy(arr, 0, newArr, 0, count);
            }
            coalesceByteArrays[stream] = newArr;
            arr = newArr;
        }
        System.arraycopy(bytes, 0, arr, count, bytes.length);
        coalesceByteCounts[stream] = needed;
    }

    private void gatherToken(int stream, long token) {
        long[] arr = coalesceTokenArrays[stream];
        int count = coalesceTokenCounts[stream];
        int needed = count + 1;
        if (arr == null || arr.length < needed) {
            int newSize = arr == null ? 4 : arr.length * 2;
            long[] newArr = new long[newSize];
            if (arr != null) {
                System.arraycopy(arr, 0, newArr, 0, count);
            }
            coalesceTokenArrays[stream] = newArr;
            arr = newArr;
        }
        arr[count] = token;
        coalesceTokenCounts[stream] = needed;
    }

    private void flushCoalesced() {
        for (int stream = 0; stream < channels.length; stream++) {
            int byteCount = coalesceByteCounts[stream];
            int tokenCount = coalesceTokenCounts[stream];
            if (byteCount == 0 || tokenCount == 0) {
                continue;
            }
            QuicStreamChannel channel = stream >= 0 && stream < channels.length ? channels[stream] : null;
            byte[] flatArray = coalesceByteArrays[stream];
            byte[] combinedBytes = new byte[byteCount];
            System.arraycopy(flatArray, 0, combinedBytes, 0, byteCount);
            ByteBuf buf = Unpooled.buffer(byteCount);
            buf.writeBytes(combinedBytes);
            long[] tokens = coalesceTokenArrays[stream];
            if (channel != null) {
                channel.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
                    boolean success = future.isSuccess();
                    for (int i = 0; i < tokenCount; i++) {
                        if (success) {
                            driver.released(tokens[i]);
                        } else {
                            driver.failed(tokens[i]);
                        }
                        settled.settled(success);
                    }
                });
            } else {
                for (int i = 0; i < tokenCount; i++) {
                    driver.failed(tokens[i]);
                    settled.settled(false);
                }
                // Release the buffer manually since it wasn't written
                buf.release();
            }
            coalesceByteCounts[stream] = 0;
            coalesceTokenCounts[stream] = 0;
        }
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
