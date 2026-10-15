// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.nio.ByteBuffer;

/**
 * An auxiliary stream's preamble (docs/protocol.md 13.3): {@code u8(version=2) || u8(type)}
 * where type is 1 REALTIME (stream 4) or 2 WORLD (stream 1). CONTROL has no second preamble.
 * The type is a plain int; this package does not depend on {@code core.routing}.
 *
 * <p>A malformed auxiliary preamble is a parser error on an activated multi-stream lane, so it
 * closes with {@code 0x11} like every other parser/ordering error (13.12). Stream 0's own
 * preamble keeps 13.2's {@code 0x1}.
 */
public final class AuxPreamble {
    public static final int VERSION = 2;
    public static final int TYPE_REALTIME = 1;
    public static final int TYPE_WORLD = 2;
    public static final int CLOSE_CODE = MultistreamProtocolException.CLOSE_ORDERING;
    /** 13.3: a REALTIME frame before the server's activation is fatal with this code. */
    public static final int CLOSE_STREAM_MISUSE = MultistreamProtocolException.CLOSE_ORDERING;

    private AuxPreamble() {}

    public static byte[] encode(int type) {
        if (type != TYPE_REALTIME && type != TYPE_WORLD) {
            throw new IllegalArgumentException("auxiliary stream type " + type);
        }
        return new byte[] {(byte) VERSION, (byte) type};
    }

    /** Incremental parser: the two bytes may arrive in one read or split. */
    public static final class Parser {
        private int version = -1;
        private int type = -1;
        private int consumed;

        /** Returns true once complete; then {@link #type()} is valid. */
        public boolean feed(ByteBuffer data) throws MultistreamProtocolException {
            if (type >= 0) {
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
            type = data.get() & 0xff;
            consumed++;
            if (type != TYPE_REALTIME && type != TYPE_WORLD) {
                throw new MultistreamProtocolException(CLOSE_CODE, "13.3",
                        "auxiliary stream type " + type);
            }
            return true;
        }

        public boolean done() {
            return type >= 0;
        }

        public int type() {
            return type;
        }

        public int consumed() {
            return consumed;
        }
    }
}
