// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Opt-in, bounded diagnostic counters. No packets or identifiers are retained. */
public final class ReceiveTrace {
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
    public final Latencies deliveryToAck = new Latencies();
    public final Latencies heldTime = new Latencies();
    public final Latencies ackToNextDelivery = new Latencies();
    public final Latencies ackEventLoopQueue = new Latencies();

    public synchronized void delivered(long unitBytes) {
        units++;
        bytes += unitBytes;
        maxBytes = Math.max(maxBytes, unitBytes);
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
                + "} ackToNextDelivery={" + ackToNextDelivery.summary() + "}";
    }
}
