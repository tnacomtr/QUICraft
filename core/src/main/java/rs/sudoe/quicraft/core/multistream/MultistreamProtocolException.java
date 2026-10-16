// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.IOException;

/**
 * A peer violated the multi-stream wire format (docs/protocol.md §13). Carries the
 * application error code the connection must be closed with, and the spec rule number in
 * the message.
 */
public final class MultistreamProtocolException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Unknown version, length or kind, an invalid frame or a non-canonical VarInt. */
    public static final int CLOSE_PROTOCOL_VIOLATION = 0x1;
    /** Ordering/resource failure: a stream used before activation, a blocked head, a limit. */
    public static final int CLOSE_ORDERING = 0x11;
    /** No common shard count between the client's offer and the server's configured range (13.2). */
    public static final int CLOSE_NO_COMMON_SHARDS = 0x10;

    private final int closeCode;

    public MultistreamProtocolException(int closeCode, String rule, String detail) {
        super("rule " + rule + ": " + detail);
        this.closeCode = closeCode;
    }

    /** The QUIC application error code to close the connection with. */
    public int closeCode() {
        return closeCode;
    }
}
