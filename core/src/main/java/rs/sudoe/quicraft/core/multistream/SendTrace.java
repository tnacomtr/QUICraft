// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Arrays;

/** Bounded opt-in server diagnostics, independent of scheduling/accounting decisions. */
public final class SendTrace {
    private static final ThreadLocal<Long> GAME_WRITE = new ThreadLocal<Long>();

    /** The bridge brackets a synchronous submission, preserving nested writes and clearing on every exit. */
    public static Long beginGameWrite(long startedNanos) {
        if (startedNanos == 0) return null;
        Long previous = GAME_WRITE.get();
        GAME_WRITE.set(startedNanos);
        return previous;
    }

    public static void endGameWrite(long startedNanos, Long previous) {
        if (startedNanos == 0) return;
        if (previous == null) GAME_WRITE.remove();
        else GAME_WRITE.set(previous);
    }

    static long gameWriteStart(long fallback) {
        Long start = GAME_WRITE.get();
        return start == null ? fallback : start;
    }

    static final class Occupancy {
        private boolean active;
        private long since;
        private long total;
        private long max;

        void update(long now, boolean next) {
            if (active == next) return;
            if (active) {
                long duration = Math.max(0, now - since);
                total += duration;
                max = Math.max(max, duration);
            }
            active = next;
            since = now;
        }

        String summary(long now) {
            long pending = active ? Math.max(0, now - since) : 0;
            return "totalMs=" + (total + pending) / 1e6 + ",maxMs=" + Math.max(max, pending) / 1e6;
        }
    }

    final ReceiveTrace.Latencies gameToEnqueue = new ReceiveTrace.Latencies();
    final ReceiveTrace.Latencies gameToSelection = new ReceiveTrace.Latencies();
    final ReceiveTrace.Latencies offeredToSelection = new ReceiveTrace.Latencies();
    final ReceiveTrace.Latencies selectionToWrite = new ReceiveTrace.Latencies();
    final ReceiveTrace.Latencies gameToWrite = new ReceiveTrace.Latencies();
    final ReceiveTrace.Latencies grantWait = new ReceiveTrace.Latencies();
    private final long[] depth = new long[8];
    private final long[] peaks = new long[8];
    private final long[] writes = new long[8];
    private final long[] bytes = new long[8];
    private final long[] maxBytesPerFlush = new long[8];
    private final Occupancy[] streamUnwritable = occupancies();
    private final Occupancy[] parentUnwritable = occupancies();
    private final Occupancy[] gated = occupancies();
    private final Occupancy poolGrantWaiting = new Occupancy();
    private long ticks;
    private long tickWrites;
    private long tickWithGrantWait;
    private long lastTick;
    private final ReceiveTrace.Latencies tickSpacing = new ReceiveTrace.Latencies();

    private static Occupancy[] occupancies() {
        Occupancy[] result = new Occupancy[8];
        for (int i = 0; i < result.length; i++) result[i] = new Occupancy();
        return result;
    }

    synchronized void enqueued(int stream, long started, long now) {
        depth[stream]++;
        peaks[stream] = Math.max(peaks[stream], depth[stream]);
        gameToEnqueue.add(now - started);
    }

    synchronized void selected(int stream, boolean first, boolean last, long started, long offered, long now) {
        if (first) {
            gameToSelection.add(now - started);
            offeredToSelection.add(now - offered);
        }
        if (last) depth[stream]--;
    }

    synchronized void written(int stream, int length, long selected, long started, long now) {
        writes[stream]++;
        bytes[stream] += length;
        maxBytesPerFlush[stream] = Math.max(maxBytesPerFlush[stream], length);
        if (selected != 0) selectionToWrite.add(now - selected);
        if (started != 0) gameToWrite.add(now - started);
    }

    synchronized void writable(int stream, boolean active, boolean streamWritable, boolean parentWritable,
                               boolean gateOpen, long now) {
        streamUnwritable[stream].update(now, active && !streamWritable);
        parentUnwritable[stream].update(now, active && !parentWritable);
        gated[stream].update(now, !gateOpen);
    }

    synchronized long writeCount() {
        long result = 0;
        for (long count : writes) result += count;
        return result;
    }

    synchronized void tick(long previousWrites, boolean awaitingGrant) {
        ticks++;
        tickWrites += writeCount() - previousWrites;
        if (awaitingGrant) tickWithGrantWait++;
    }

    synchronized void tickStarted(long now) {
        if (lastTick != 0) tickSpacing.add(now - lastTick);
        lastTick = now;
    }

    synchronized void grantWaiting(boolean waiting, long now) {
        poolGrantWaiting.update(now, waiting);
    }

    synchronized String summary(long now) {
        StringBuilder out = new StringBuilder("queuedPeakPerStream=" + Arrays.toString(peaks)
                + " notFullySelectedPerStream=" + Arrays.toString(depth)
                + " gameToEnqueue={" + gameToEnqueue.summary() + "} gameToSelection={" + gameToSelection.summary()
                + "} offeredToSelection={" + offeredToSelection.summary() + "} selectionToWrite={"
                + selectionToWrite.summary() + "} gameToWrite={" + gameToWrite.summary()
                + "} poolGrantWait={" + grantWait.summary() + "} ticks=" + ticks + " writesInsideTick=" + tickWrites
                + " ticksAwaitingGrant=" + tickWithGrantWait + " tickSpacing={" + tickSpacing.summary()
                + "} poolGrantWaiting={" + poolGrantWaiting.summary(now) + "}");
        for (int i = 0; i < writes.length; i++) {
            out.append(" stream").append(i).append("={writes=").append(writes[i])
                    .append(",flushes=").append(writes[i]).append(",bytes=").append(bytes[i])
                    .append(",writesPerFlush=1,meanBytesPerFlush=").append(writes[i] == 0 ? 0 : bytes[i] / writes[i])
                    .append(",maxBytesPerFlush=").append(maxBytesPerFlush[i])
                    .append(",streamUnwritable={").append(streamUnwritable[i].summary(now))
                    .append("},parentUnwritable={").append(parentUnwritable[i].summary(now))
                    .append("},writeGate={").append(gated[i].summary(now)).append("}}");
        }
        return out.toString();
    }
}
