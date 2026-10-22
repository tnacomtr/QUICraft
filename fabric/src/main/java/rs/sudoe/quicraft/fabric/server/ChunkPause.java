// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.server;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import rs.sudoe.quicraft.fabric.QuicraftFabric;

/**
 * Producer pause for chunk batches on a {@code /3} connection (docs/protocol.md 13.20). While the
 * connection's outbound queue is under pressure, the server must not start a new chunk batch: the
 * pending chunks stay pending and go out once the pressure clears, so nothing is dropped and the
 * causal order of the stream is unchanged. Started units always finish.
 *
 * <p>The decision is read from the connection's Netty channel. {@link #attach} stores the pressure
 * supplier when the join activates; a TCP or {@code /2} connection has none, so {@link #paused}
 * is false for it and vanilla runs unchanged.
 *
 * <p><b>Fail safe.</b> Every method here catches everything: a missing channel, a missing
 * attribute or a throwing supplier all mean "not paused", so the caller takes the vanilla path.
 * Nothing here ever throws into the server thread.
 *
 * <p>Threading: {@link #paused} runs on the server thread; the supplier is thread-safe.
 */
public final class ChunkPause {
    private static final AttributeKey<Pressure> KEY = AttributeKey.valueOf("quicraft.chunkPause");
    /** Calls to {@link #paused} that returned true: one per server tick a chunk batch was held back. */
    private static final AtomicLong PAUSED_CALLS = new AtomicLong();
    /** One INFO line per connection, the first time a batch start is paused. */
    private static volatile Consumer<String> infoSink = message -> QuicraftFabric.LOG.info(message);

    private ChunkPause() {}

    /** The pressure supplier of one connection, and whether its one log line has been written. */
    private static final class Pressure {
        final BooleanSupplier underPressure;
        final AtomicBoolean logged = new AtomicBoolean();

        Pressure(BooleanSupplier underPressure) {
            this.underPressure = underPressure;
        }
    }

    /**
     * Hook (join activation): stores the connection's outbound-pressure check on its channel. A null
     * channel or supplier is ignored; any failure leaves the connection unpaused. Never throws.
     */
    public static void attach(@Nullable Channel channel, @Nullable BooleanSupplier underPressure) {
        try {
            if (channel != null && underPressure != null) {
                channel.attr(KEY).set(new Pressure(underPressure));
            }
        } catch (Throwable ignored) {
            // Without the check the connection keeps vanilla chunk pacing, which is the fallback.
        }
    }

    /**
     * True while the connection of {@code channel} is under outbound pressure, so the caller must not
     * start a chunk batch now. False for a null channel, no attribute, a throwing supplier, or any other
     * failure. Never throws.
     */
    public static boolean paused(@Nullable Channel channel) {
        try {
            if (channel == null) {
                return false;
            }
            Pressure pressure = channel.attr(KEY).get();
            if (pressure == null || !pressure.underPressure.getAsBoolean()) {
                return false;
            }
            PAUSED_CALLS.incrementAndGet();
            logOnce(pressure);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Calls to {@link #paused} that returned true since class load, for tests and diagnostics. */
    public static long pausedCalls() {
        return PAUSED_CALLS.get();
    }

    private static void logOnce(Pressure pressure) {
        try {
            if (pressure.logged.compareAndSet(false, true)) {
                infoSink.accept("QUICraft v3 chunk pause: outbound pressure on this connection; new chunk "
                        + "batches wait until the queue drains (protocol 13.20). Logged once per connection.");
            }
        } catch (Throwable ignored) {
            // Logging is best effort; the pause itself has already been decided.
        }
    }

    /** Tests only: replaces the INFO sink. Returns the previous sink so the caller can restore it. */
    static Consumer<String> setInfoSink(Consumer<String> sink) {
        Consumer<String> previous = infoSink;
        infoSink = sink;
        return previous;
    }
}
