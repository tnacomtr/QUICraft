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
 * stream during a single {@link #drain()} call and writes one combined buffer per stream. The cap is soft: a stream
 * stops being polled once it gathered 64 KiB, so one buffer is at most 64 KiB plus one slice. A drain re-entered
 * from a write completion only makes the running drain go round again, so a stream's bytes stay in order. Otherwise each slice is written individually with the behavior unchanged.
 */
public final class QuicStreamWriteSink {
    private static final boolean COALESCE_DEFAULT = Boolean.parseBoolean(
            System.getProperty("quicraft.coalesceWrites", "false"));
    /** Soft per-stream cap of one combined write; experiment knob {@code quicraft.coalesceMaxBytes}. */
    private static final int COALESCE_MAX_BYTES = Math.max(1, Integer.getInteger("quicraft.coalesceMaxBytes", 64 * 1024));

    private final SessionSendDriver driver;
    private final QuicStreamChannel[] channels;
    private final IntPredicate writable = this::isWritable;
    private final IntPredicate gate;
    private final Settled settled;
    /** Test hook: runs on the loop after each poll returned a slice and before that slice is written. */
    private volatile Runnable beforeWrite;
    private boolean coalesce;
    /** Bytes gathered per stream in the running coalesced drain; all zero outside a drain's poll loop. */
    private final int[] gatheredBytes;
    /** True while a coalesced drain runs; a nested drain() then only sets {@link #drainAgain}. */
    private boolean draining;
    private boolean drainAgain;

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
        // A write can complete inline and re-enter drain() through settled or the writability watch. A nested
        // drain must not poll: it would take a later slice of a stream whose earlier gathered slices this drain has
        // not written yet. It only asks the running drain to go round again.
        if (draining) {
            drainAgain = true;
            return;
        }
        draining = true;
        try {
            do {
                drainAgain = false;
                drainCoalesced();
            } while (drainAgain);
        } finally {
            draining = false;
        }
    }

    /** One coalesced round: gather up to the cap per stream, then write one buffer per stream. */
    private void drainCoalesced() {
        byte[][][] slices = null;
        long[][] tokens = null;
        int[] counts = null;
        boolean capped = false;
        try {
            for (;;) {
                SessionSendDriver.Write write = driver.poll(writable);
                if (write == null) {
                    break;
                }
                int stream = write.stream();
                if (stream < 0 || stream >= channels.length || channels[stream] == null) {
                    driver.failed(write.token());
                    continue;
                }
                if (slices == null) {
                    slices = new byte[channels.length][][];
                    tokens = new long[channels.length][];
                    counts = new int[channels.length];
                }
                int count = counts[stream];
                if (slices[stream] == null) {
                    slices[stream] = new byte[8][];
                    tokens[stream] = new long[8];
                } else if (count == slices[stream].length) {
                    slices[stream] = Arrays.copyOf(slices[stream], count * 2);
                    tokens[stream] = Arrays.copyOf(tokens[stream], count * 2);
                }
                // Owned from here: an exception below fails it with the rest of the gathered tokens.
                byte[] bytes = write.bytes();
                slices[stream][count] = bytes;
                tokens[stream][count] = write.token();
                counts[stream] = count + 1;
                gatheredBytes[stream] += bytes.length;
                if (gatheredBytes[stream] >= COALESCE_MAX_BYTES) {
                    capped = true;
                }
                Runnable hook = beforeWrite;
                if (hook != null) {
                    hook.run();
                }
                if (driver.trace() != null) {
                    driver.trace().written(stream, bytes.length, write.traceSelectedNanos,
                            write.traceStartedNanos, System.nanoTime());
                }
            }
        } catch (RuntimeException | Error e) {
            failAll(tokens, counts, 0);
            throw e;
        } finally {
            Arrays.fill(gatheredBytes, 0);
        }
        if (slices == null) {
            return;
        }
        for (int stream = 0; stream < slices.length; stream++) {
            if (counts[stream] == 0) {
                continue;
            }
            if (driver.isClosed()) {
                // An inline failure closed the session and released every charge; submit nothing more.
                failAll(tokens, counts, stream);
                return;
            }
            try {
                writeCombined(stream, slices[stream], tokens[stream], counts[stream]);
            } catch (RuntimeException | Error e) {
                // writeCombined throws before its listener is attached, so this stream's tokens are unsettled too.
                failAll(tokens, counts, stream);
                throw e;
            }
            counts[stream] = 0;
        }
        if (capped) {
            // A stream stopped at the cap may still have queued records and no writability change to come.
            drainAgain = true;
        }
    }

    private void failAll(long[][] tokens, int[] counts, int fromStream) {
        if (tokens == null) {
            return;
        }
        for (int stream = fromStream; stream < tokens.length; stream++) {
            for (int i = 0; i < counts[stream]; i++) {
                driver.failed(tokens[stream][i]);
            }
            counts[stream] = 0;
        }
    }

    /**
     * Writes one stream's gathered slices as one buffer. Its future settles every gathered token with the driver
     * first, then tells the owner once per token.
     */
    private void writeCombined(int stream, byte[][] slices, long[] tokens, int count) {
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += slices[i].length;
        }
        byte[] combined = new byte[total];
        int at = 0;
        for (int i = 0; i < count; i++) {
            System.arraycopy(slices[i], 0, combined, at, slices[i].length);
            at += slices[i].length;
        }
        channels[stream].writeAndFlush(Unpooled.wrappedBuffer(combined)).addListener(
                (ChannelFutureListener) future -> {
                    boolean success = future.isSuccess();
                    for (int i = 0; i < count; i++) {
                        if (success) {
                            driver.released(tokens[i]);
                        } else {
                            driver.failed(tokens[i]);
                        }
                    }
                    for (int i = 0; i < count; i++) {
                        settled.settled(success);
                    }
                });
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
