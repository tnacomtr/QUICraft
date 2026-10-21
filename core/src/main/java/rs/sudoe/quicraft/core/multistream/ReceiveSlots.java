// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/**
 * Client-side receive reservations for the N+3 logical streams (protocol.md 13.12, clientbound).
 *
 * <p>Every stream owns a non-borrowable slot of {@link #BYTES_PER_STREAM} bytes and
 * {@link #RECORDS_PER_STREAM} records. A stream admits at most one head scheduling unit (one
 * record or one complete bundle) into application buffering at a time, so {@link #admitHead}
 * refuses while that stream's previous head is still held. Parser prefixes, partial records and
 * staged bundle bytes are charged to the same slot. One connection-wide execution workspace of
 * {@link #EXECUTION_WORKSPACE} bytes covers decompression and decoded data for the single unit
 * that is being applied.
 *
 * <p>Refusals are normal flow control, not protocol errors: the caller leaves the stream unread
 * until the held unit commits.
 */
public final class ReceiveSlots {
    /** Per-stream byte slot, B = 2 MiB + 128 bytes (13.12). */
    public static final long BYTES_PER_STREAM = 2L * 1024 * 1024 + 128;
    /** Per-stream record slots, including delimiters and partials (13.12). */
    public static final int RECORDS_PER_STREAM = 128;
    /**
     * The ACK stream's record cap in wire bytes, length prefix included, and in inflated bytes (13.21). A larger
     * record closes 0x11.
     */
    public static final int ACK_RECORD_MAX_BYTES = 64;
    /**
     * The ACK stream's protected slot: outside the shard pool, one record and at least the prefix staging
     * bytes, so a provisional head always fits (13.12, 13.21).
     */
    public static final long ACK_SLOT_BYTES = Math.max(ACK_RECORD_MAX_BYTES, SessionReadPump.PREFIX_STAGE);
    /** Connection-wide execution workspace for decompression and decoded data (13.12). */
    public static final long EXECUTION_WORKSPACE = 8L * 1024 * 1024;

    private final int ackStream;
    private final long[] heldBytes;
    private final int[] heldRecords;
    private final boolean[] headHeld;
    private long workspace;

    /**
     * Creates slots for the negotiated N+3 streams.
     *
     * @param chunks negotiated chunk count N, 1..5 (13.12 caps the chunk count at six)
     */
    public ReceiveSlots(int chunks) {
        LogicalStream.validateShardCount(chunks);
        int streams = chunks + 3;
        ackStream = streams - 1;
        heldBytes = new long[streams];
        heldRecords = new int[streams];
        headHeld = new boolean[streams];
    }

    /**
     * Admits one head unit on a stream, reserving its full wire size before any read (13.12).
     *
     * @return false when the stream already holds a head, or the unit exceeds the stream's byte or
     *     record slot; nothing is reserved in that case
     */
    public boolean admitHead(int stream, long bytes, int records) {
        checkStream(stream);
        if (headHeld[stream] || bytes < 0 || records < 0) {
            return false;
        }
        if (stream == ackStream) {
            if (bytes > ACK_SLOT_BYTES || records > 1) {
                return false;
            }
        } else if (bytes > BYTES_PER_STREAM || records > RECORDS_PER_STREAM) {
            return false;
        }
        headHeld[stream] = true;
        heldBytes[stream] = bytes;
        heldRecords[stream] = records;
        return true;
    }

    /** The logical index of the ACK stream, N+2. */
    public int ackStream() {
        return ackStream;
    }

    /**
     * The connection's total receive reservation in bytes: the N+2 stream slots, the small ACK slot and the
     * execution workspace (13.12, 13.21). The ACK slot is counted here, never borrowed from the shard slots.
     */
    public long connectionBytes() {
        return (long) (heldBytes.length - 1) * BYTES_PER_STREAM + ACK_SLOT_BYTES + EXECUTION_WORKSPACE;
    }

    /** Releases the stream's head unit once it has committed (its D watermark advanced, 13.10). */
    public void releaseHead(int stream) {
        checkStream(stream);
        if (!headHeld[stream]) {
            throw new IllegalStateException("no head held on stream " + stream);
        }
        headHeld[stream] = false;
        heldBytes[stream] = 0;
        heldRecords[stream] = 0;
    }

    /** Reports whether the stream currently holds a head unit. */
    public boolean holdsHead(int stream) {
        checkStream(stream);
        return headHeld[stream];
    }

    /** Returns the bytes charged to the stream's held head. */
    public long heldBytes(int stream) {
        checkStream(stream);
        return heldBytes[stream];
    }

    /** Returns the records charged to the stream's held head. */
    public int heldRecords(int stream) {
        checkStream(stream);
        return heldRecords[stream];
    }

    /** Reserves decoded/decompression workspace before allocation; false means fail closed (13.12). */
    public boolean reserveWorkspace(long amount) {
        if (amount < 0 || amount > EXECUTION_WORKSPACE - workspace) {
            return false;
        }
        workspace += amount;
        return true;
    }

    /** Returns workspace after the unit's execution completes. */
    public void releaseWorkspace(long amount) {
        if (amount < 0 || amount > workspace) {
            throw new IllegalStateException("unbalanced workspace");
        }
        workspace -= amount;
    }

    /** Returns workspace currently reserved. */
    public long workspaceInUse() {
        return workspace;
    }

    private void checkStream(int stream) {
        if (stream < 0 || stream >= headHeld.length) {
            throw new IllegalArgumentException("stream " + stream);
        }
    }
}
