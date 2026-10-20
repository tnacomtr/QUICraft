// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.util.Arrays;

/** Opt-in, bounded diagnostic counters. No packets or identifiers are retained. */
public final class ReceiveTrace {
    public enum WaitReason {
        ACK_EVENT_LOOP, DRIVE_CPU, NO_LOCAL_BYTES, PARTIAL_UNIT, BUNDLE_PARTIAL,
        READ_GRANT_CONSUMED, BUFFERED_AWAIT_PUMP, ELIGIBLE_HEAD, WORKSPACE,
        CONTROL_SEQUENCE, CHUNK_SEQUENCE_FENCE_OR_JOIN_UNKNOWN, REALTIME_SEQUENCE,
        BUNDLE_SEQUENCE, EPOCH_TRANSITION
    }

    /** Disjoint, event-boundary attribution. Only completed ack-to-delivery gaps enter totals. */
    public static final class Waits {
        private final long[] total = new long[WaitReason.values().length];
        private final long[] max = new long[total.length];
        private final long[] gapTotal = new long[total.length];
        private final long[] gapMax = new long[total.length];
        private long since;
        private boolean active;
        private WaitReason reason;

        public void start(long now) {
            Arrays.fill(gapTotal, 0);
            Arrays.fill(gapMax, 0);
            since = now;
            reason = WaitReason.ACK_EVENT_LOOP;
            active = true;
        }

        private void account(long now) {
            long elapsed = Math.max(0, now - since);
            int i = reason.ordinal();
            gapTotal[i] += elapsed;
            gapMax[i] = Math.max(gapMax[i], elapsed);
        }

        public void state(long now, WaitReason next) {
            if (!active || next == reason) return;
            account(now);
            since = now;
            reason = next;
        }

        public void finish(long now) {
            if (!active) return;
            account(now);
            for (int i = 0; i < total.length; i++) {
                total[i] += gapTotal[i];
                max[i] = Math.max(max[i], gapMax[i]);
            }
            active = false;
        }

        public String summary(long now) {
            StringBuilder out = new StringBuilder();
            for (WaitReason r : WaitReason.values()) {
                int i = r.ordinal();
                out.append(r).append("={totalMs=").append(total[i] / 1e6)
                        .append(",maxSegmentMs=").append(max[i] / 1e6).append("} ");
            }
            return out.append("pendingReason=").append(active ? reason : "none")
                    .append(" pendingSegmentMs=").append(active ? Math.max(0, now - since) / 1e6 : 0).toString();
        }
    }

    public static boolean enabled() {
        return Boolean.getBoolean("quicraft.v3.trace");
    }

    /** 100 us buckets, with an overflow bucket. Percentiles are bucket upper bounds, not exact values. */
    public static final class Latencies {
        private final long[] buckets = new long[4096];
        private long count;
        private long total;
        private long max;

        public synchronized void add(long nanos) {
            nanos = Math.max(0, nanos);
            buckets[(int) Math.min(buckets.length - 1, nanos / 100000)]++;
            count++;
            total += nanos;
            max = Math.max(max, nanos);
        }

        private double percentile(double fraction) {
            if (count == 0) return 0;
            long rank = (long) Math.ceil(count * fraction);
            long seen = 0;
            for (int i = 0; i < buckets.length; i++) {
                seen += buckets[i];
                if (seen >= rank) return (i == buckets.length - 1 ? max : (i + 1) * 100000L) / 1e6;
            }
            throw new AssertionError("histogram count");
        }

        public synchronized String summary() {
            return "n=" + count + ",p50UpperMs=" + percentile(.5) + ",p95UpperMs=" + percentile(.95)
                    + ",maxMs=" + max / 1e6 + ",totalMs=" + total / 1e6;
        }
    }

    private long units;
    private long bytes;
    private long maxBytes;
    private long immediate;
    private long held;
    private long peakPackets;
    private long peakBytes;
    private final long[] unitsPerStream = new long[8];
    public final Waits waits = new Waits();
    public final Latencies deliveryToAck = new Latencies();
    public final Latencies heldTime = new Latencies();
    public final Latencies ackToNextDelivery = new Latencies();
    public final Latencies ackEventLoopQueue = new Latencies();

    public synchronized void delivered(long unitBytes) {
        units++;
        bytes += unitBytes;
        maxBytes = Math.max(maxBytes, unitBytes);
    }

    public synchronized void deliveredOnStream(int stream) {
        unitsPerStream[stream]++;
    }

    public synchronized void backlog(long packets, long pendingBytes) {
        peakPackets = Math.max(peakPackets, packets);
        peakBytes = Math.max(peakBytes, pendingBytes);
    }

    public synchronized void acknowledged(long deliveryNanos, long heldNanos, long now) {
        deliveryToAck.add(now - deliveryNanos);
        if (heldNanos != 0) {
            held++;
            heldTime.add(now - heldNanos);
        } else {
            immediate++;
        }
    }

    public synchronized String summary() {
        return "units=" + units + " immediateAcks=" + immediate + " heldAcks=" + held
                + " peakUnrunPackets=" + peakPackets + " peakUnrunBytes=" + peakBytes
                + " decodedBytes=" + bytes + " meanBytesPerUnit=" + (units == 0 ? 0 : bytes / units)
                + " maxBytesPerUnit=" + maxBytes + " deliveryToAck={" + deliveryToAck.summary()
                + "} heldTime={" + heldTime.summary() + "} ackEventLoopQueue={" + ackEventLoopQueue.summary()
                + "} ackToNextDelivery={" + ackToNextDelivery.summary() + "}";
    }

    public String timingSummary() {
        return "ackEventLoopQueue={" + ackEventLoopQueue.summary()
                + "} ackToNextDelivery={" + ackToNextDelivery.summary() + "} unitsPerStream="
                + Arrays.toString(unitsPerStream) + " waits=" + waits.summary(System.nanoTime());
    }
}
