// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.nio.ByteBuffer;

/**
 * An auxiliary stream's preamble (docs/protocol.md 13.3): {@code u8(version=2) || u8(logical index)}.
 * The logical index is a plain int; this package does not depend on {@code core.routing}.
 *
 * <p>A malformed auxiliary preamble is a parser error on an activated multi-stream lane, so it
 * closes with {@code 0x11} like every other parser/ordering error (13.12). Stream 0's own
 * preamble keeps 13.2's {@code 0x1}.
 */
public final class AuxPreamble {
    public static final int VERSION = 2;
    /** @deprecated Use {@link LogicalStream#REALTIME}; retained for legacy callers. */
    @Deprecated public static final int TYPE_REALTIME = LogicalStream.REALTIME;
    /** @deprecated CHUNK streams now have individual logical indices starting at 2. */
    @Deprecated public static final int TYPE_WORLD = 2;
    public static final int CLOSE_CODE = MultistreamProtocolException.CLOSE_ORDERING;
    /** 13.3: a REALTIME frame before the server's activation is fatal with this code. */
    public static final int CLOSE_STREAM_MISUSE = MultistreamProtocolException.CLOSE_ORDERING;

    private AuxPreamble() {}

    public static byte[] encode(int logicalStreamIndex) {
        if (logicalStreamIndex < LogicalStream.REALTIME || logicalStreamIndex > 7) {
            throw new IllegalArgumentException("auxiliary logical stream index " + logicalStreamIndex);
        }
        return new byte[] {(byte) VERSION, (byte) logicalStreamIndex};
    }

    /** Incremental parser: the two bytes may arrive in one read or split. */
    public static final class Parser {
        private int version = -1;
        private int logicalStreamIndex = -1;
        private int consumed;

        /** Returns true once complete; then {@link #logicalStreamIndex()} is valid. */
        public boolean feed(ByteBuffer data) throws MultistreamProtocolException {
            if (logicalStreamIndex >= 0) {
                return true;
            }
            if (version < 0) {
                if (!data.hasRemaining()) {
                    return false;
                }
                version = data.get() & 0xff;
                consumed++;
                if (version != VERSION) {
                    throw new MultistreamProtocolException(CLOSE_CODE, "13.3",
                            "auxiliary preamble version " + version);
                }
            }
            if (!data.hasRemaining()) {
                return false;
            }
            logicalStreamIndex = data.get() & 0xff;
            consumed++;
            if (logicalStreamIndex < LogicalStream.REALTIME || logicalStreamIndex > 7) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.3",
                        "auxiliary logical stream index " + logicalStreamIndex);
            }
            return true;
        }

        public boolean done() {
            return logicalStreamIndex >= 0;
        }

        public int logicalStreamIndex() {
            return logicalStreamIndex;
        }

        /** @deprecated Use {@link #logicalStreamIndex()}. */
        @Deprecated
        public int type() {
            return logicalStreamIndex();
        }

        public int consumed() {
            return consumed;
        }
    }
}
