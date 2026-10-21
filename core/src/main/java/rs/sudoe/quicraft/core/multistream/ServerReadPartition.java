// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

/**
 * Serverbound read partition inside the 24 KiB per-connection admission share (protocol.md 13.12).
 *
 * <p>The share is split as {@code 16 KiB + 2N + 1 + P + 512 <= 24 KiB}: 16 KiB of native
 * stream exposure, the guard-read copies, the protected read/decode area P and the separate
 * 512-byte complete-record REALTIME wire slot. P is fixed per negotiated N; at N=6 it is 7667
 * bytes. Inside P:
 * <ul>
 *   <li>a 160-byte prefix slice for staged envelope and length prefixes, never lent to excess;</li>
 *   <li>a 2 KiB REALTIME parse/output-copy slice, never lent to CONTROL;</li>
 *   <li>the CONTROL body allowance, P - 2 KiB - 160 (5459 bytes at N=6). This is stricter than the
 *       "at most P - 2 KiB" bound because the prefix slice is separate; the bound is satisfied.</li>
 * </ul>
 * Protected reads cannot be refused by the node pool. A CONTROL record whose footprint does not
 * fit the remaining CONTROL allowance must use refusable excess instead (see {@link #reserve}).
 *
 * <p>Not thread-safe; owned by the connection's receive path.
 */
public final class ServerReadPartition {
    /** Per-connection admission share, 24 KiB (13.12). */
    public static final int SHARE = 24 * 1024;
    /** Native exposure bound used in the share equation, 16 KiB (13.12). */
    public static final int NATIVE_EXPOSURE = 16 * 1024;
    /** Prefix staging slice inside P, 160 bytes (13.12). */
    public static final int PREFIX_SLICE = 160;
    /** REALTIME parse/output-copy slice inside P, 2 KiB (13.12). */
    public static final int REALTIME_SLICE = 2 * 1024;
    /** Separate non-borrowable REALTIME complete-record wire slot, 512 bytes (13.12). */
    public static final int REALTIME_WIRE_CAP = 512;
    /** Largest legal REALTIME record_length, excluding its two-byte prefix (13.12). */
    public static final int REALTIME_RECORD_CAP = 510;
    /** Largest decoded REALTIME payload or declared Data Length, 1024 bytes (13.12). */
    public static final int REALTIME_DECODED_CAP = 1024;
    /** Largest single read grant, 16 KiB (13.12). */
    public static final int MAX_READ_GRANT = 16 * 1024;

    private final int protectedBytes;
    private final int controlAllowance;
    private int prefixInUse;
    private int controlInUse;
    private int realtimeInUse;

    /**
     * Builds the partition for negotiated chunk count N.
     *
     * @param chunks negotiated chunk count N, 1..5
     */
    public ServerReadPartition(int chunks) {
        LogicalStream.validateShardCount(chunks);
        protectedBytes = SHARE - NATIVE_EXPOSURE - (2 * (chunks + 1) + 1) - REALTIME_WIRE_CAP;
        controlAllowance = protectedBytes - REALTIME_SLICE - PREFIX_SLICE;
        if (controlAllowance <= 0) {
            throw new IllegalStateException("read partition too small for N=" + chunks);
        }
    }

    /** Returns P, the total protected read/decode capacity for this connection (13.12). */
    public int protectedBytes() {
        return protectedBytes;
    }

    /** Returns the CONTROL body allowance inside P (13.12). */
    public int controlAllowance() {
        return controlAllowance;
    }

    /**
     * Reserves protected bytes for a prefix staging copy from the fixed 160-byte slice.
     *
     * @return false when the slice is full; prefix staging never falls back to excess
     */
    public boolean reservePrefix(int bytes) {
        if (bytes < 0 || bytes > PREFIX_SLICE - prefixInUse) {
            return false;
        }
        prefixInUse += bytes;
        return true;
    }

    /** Prefix staging bytes currently held; never above {@link #PREFIX_SLICE}. */
    public int prefixInUse() {
        return prefixInUse;
    }

    /** Returns prefix staging bytes. */
    public void releasePrefix(int bytes) {
        if (bytes < 0 || bytes > prefixInUse) {
            throw new IllegalStateException("unbalanced prefix slice");
        }
        prefixInUse -= bytes;
    }

    /**
     * Reserves bytes of an in-share read or record footprint for CONTROL or REALTIME.
     *
     * <p>CONTROL: true only if the bytes fit the remaining CONTROL allowance; a false result means
     * the record needs refusable excess. REALTIME: true only if the bytes fit its protected slice.
     * Other streams are never admitted by this partition and return false.
     */
    public boolean reserve(int stream, int bytes) {
        if (bytes < 0) {
            return false;
        }
        if (stream == LogicalStream.CONTROL) {
            if (bytes > controlAllowance - controlInUse) {
                return false;
            }
            controlInUse += bytes;
            return true;
        }
        if (stream == LogicalStream.REALTIME) {
            if (bytes > REALTIME_SLICE - realtimeInUse) {
                return false;
            }
            realtimeInUse += bytes;
            return true;
        }
        return false;
    }

    /** Returns bytes reserved with {@link #reserve}; unused read grants are returned here too. */
    public void release(int stream, int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes");
        }
        if (stream == LogicalStream.CONTROL) {
            if (bytes > controlInUse) {
                throw new IllegalStateException("unbalanced CONTROL partition");
            }
            controlInUse -= bytes;
        } else if (stream == LogicalStream.REALTIME) {
            if (bytes > realtimeInUse) {
                throw new IllegalStateException("unbalanced REALTIME partition");
            }
            realtimeInUse -= bytes;
        } else {
            throw new IllegalArgumentException("stream " + stream);
        }
    }

    /** Returns the bytes still available in-share to a stream. */
    public int available(int stream) {
        if (stream == LogicalStream.CONTROL) {
            return controlAllowance - controlInUse;
        }
        if (stream == LogicalStream.REALTIME) {
            return REALTIME_SLICE - realtimeInUse;
        }
        return 0;
    }

    /**
     * Computes a read grant: {@code min(16 KiB, remaining charged capacity, remaining unit bytes)}
     * (13.12). The caller reserves the whole grant before calling read and returns the unused part.
     */
    public int readGrant(int stream, int remainingUnitBytes) {
        if (remainingUnitBytes <= 0) {
            return 0;
        }
        return Math.min(MAX_READ_GRANT, Math.min(available(stream), remainingUnitBytes));
    }

    /**
     * Chooses the next serverbound stream to read from its in-share partition (13.12, "protected
     * REALTIME heads served first"). REALTIME is considered before CONTROL. A stream is chosen only if
     * it has demand and a non-zero read grant inside P. Nothing here draws on the node pool: when
     * neither stream can be granted in-share, the result is -1 and any CONTROL excess is the caller's
     * refusable concern, never a protected grant.
     *
     * @param realtimeRemaining unread bytes of the current REALTIME unit, 0 if none
     * @param controlRemaining unread bytes of the current CONTROL unit, 0 if none
     * @return LogicalStream.REALTIME, LogicalStream.CONTROL, or -1
     */
    public int nextReadStream(int realtimeRemaining, int controlRemaining) {
        if (readGrant(LogicalStream.REALTIME, realtimeRemaining) > 0) {
            return LogicalStream.REALTIME;
        }
        if (readGrant(LogicalStream.CONTROL, controlRemaining) > 0) {
            return LogicalStream.CONTROL;
        }
        return -1;
    }

    /**
     * Checks a REALTIME record against the hard per-record caps (13.12). A record that fails is peer
     * misconduct and closes 0x11.
     *
     * @param recordLength record_length as encoded, excluding its own prefix
     * @param declaredDataLength Data Length prefix value, or 0 when compression is disabled
     * @param uncompressedLength uncompressed inner payload length
     */
    public static boolean realtimeRecordLegal(long recordLength, long declaredDataLength, long uncompressedLength) {
        return recordLength >= 0 && recordLength <= REALTIME_RECORD_CAP
                && declaredDataLength >= 0 && declaredDataLength <= REALTIME_DECODED_CAP
                && uncompressedLength >= 0 && uncompressedLength <= REALTIME_DECODED_CAP;
    }
}
