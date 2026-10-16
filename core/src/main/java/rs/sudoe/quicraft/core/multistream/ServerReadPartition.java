// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Serverbound read-credit partition within the 24 KiB connection share (13.12-13.13). */
public final class ServerReadPartition {
    public static final int SHARE = 24 * 1024;
    public static final int PREFIX = 160;
    public static final int REALTIME_SLICE = 2 * 1024;
    public static final int REALTIME_WIRE_CAP = 512;
    public static final int REALTIME_RECORD_CAP = 510;
    public static final int REALTIME_DECODED_CAP = 1024;
    private final int controlLimit;
    private final int totalLimit;
    private int control, realtime, other;

    /** Builds bounds for negotiated chunk count N, using the P share calculation (13.12). */
    public ServerReadPartition(int chunks) {
        LogicalStream.validateShardCount(chunks);
        totalLimit = SHARE - 16 * 1024 - 2 * chunks - 1 - REALTIME_WIRE_CAP;
        controlLimit = Math.max(0, totalLimit - PREFIX - REALTIME_SLICE);
    }

    /** Returns CONTROL's maximum admitted bytes, including its prefix slice (13.12). */
    public int controlLimit() { return controlLimit + PREFIX; }
    /** Returns the P share available to this direction (13.12). */
    public int totalLimit() { return totalLimit; }

    /** Reserves serverbound bytes; protected REALTIME capacity is not spendable by other streams (13.12). */
    public boolean reserve(int stream, int amount) {
        if (amount < 0 || amount > totalLimit) return false;
        if (stream == LogicalStream.REALTIME) {
            if (amount > REALTIME_WIRE_CAP || realtime + amount > REALTIME_SLICE) return false;
            realtime += amount; return true;
        }
        if (stream == LogicalStream.CONTROL) {
            if (control + amount > controlLimit + PREFIX) return false;
            control += amount; return true;
        }
        if (other + amount > totalLimit - control - realtime) return false;
        other += amount; return true;
    }

    /** Releases bytes previously reserved from a stream (13.12). */
    public void release(int stream, int amount) {
        if (amount < 0) throw new IllegalArgumentException("amount");
        if (stream == LogicalStream.REALTIME) realtime = subtract(realtime, amount);
        else if (stream == LogicalStream.CONTROL) control = subtract(control, amount);
        else other = subtract(other, amount);
    }
    private static int subtract(int held, int amount) {
        if (amount > held) throw new IllegalStateException("unbalanced read partition");
        return held - amount;
    }
}
