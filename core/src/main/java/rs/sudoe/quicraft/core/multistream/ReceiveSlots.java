// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/** Per-stream admission slots for the /3 receiver (13.12). */
public final class ReceiveSlots {
    public static final long BYTES_PER_STREAM = 2L * 1024 * 1024 + 128;
    public static final int RECORDS_PER_STREAM = 128;
    public static final long EXECUTION_WORKSPACE = 8L * 1024 * 1024;
    private final long[] bytes;
    private final int[] records;
    private long workspace;

    /** Creates slots for the negotiated N+2 streams. */
    public ReceiveSlots(int chunks) {
        LogicalStream.validateShardCount(chunks);
        bytes = new long[chunks + 2]; records = new int[chunks + 2];
    }

    /** Reserves one head unit against its stream's independent byte and record caps (13.12). */
    public boolean reserve(int stream, long amount) {
        checkStream(stream);
        if (amount < 0 || bytes[stream] + amount > BYTES_PER_STREAM || records[stream] == RECORDS_PER_STREAM) return false;
        bytes[stream] += amount; records[stream]++; return true;
    }

    /** Releases a previously reserved unit (13.12). */
    public void release(int stream, long amount) {
        checkStream(stream);
        if (amount < 0 || amount > bytes[stream] || records[stream] == 0) throw new IllegalStateException("unbalanced receive slot");
        bytes[stream] -= amount; records[stream]--;
    }

    /** Debits the shared execution workspace before decoded allocation (13.12). */
    public boolean reserveWorkspace(long amount) {
        if (amount < 0 || amount > EXECUTION_WORKSPACE - workspace) return false;
        workspace += amount; return true;
    }

    /** Returns decoded allocation workspace after execution (13.12). */
    public void releaseWorkspace(long amount) {
        if (amount < 0 || amount > workspace) throw new IllegalStateException("unbalanced workspace");
        workspace -= amount;
    }

    private void checkStream(int stream) {
        if (stream < 0 || stream >= bytes.length) throw new IllegalArgumentException("stream");
    }
}
