// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Stream 0's version 2 preamble (docs/protocol.md 13.2):
 * {@code VarInt(length) || u8(version=2) || u8(token_length) || token}, token length 0 or
 * 16, length exactly 2 or 18. Unknown version or length closes with {@code 0x1}. This is the
 * version-aware parser {@code /3} requires; it does not replace the v1
 * {@code transport.Preamble}.
 */
public final class PreambleV2 {
    public static final int VERSION = 2;
    public static final int TOKEN_LENGTH = 16;
    /** length with no token: version + token length. */
    public static final int LENGTH_NO_TOKEN = 2;
    /** length with a token: version + token length + 16-byte token. */
    public static final int LENGTH_TOKEN = 18;
    public static final int MAX_LENGTH = LENGTH_TOKEN;

    private PreambleV2() {}

    /** {@code token} null or empty for none, otherwise exactly 16 bytes. */
    public static byte[] encode(byte[] token) {
        int tokenLength = token == null ? 0 : token.length;
        if (tokenLength != 0 && tokenLength != TOKEN_LENGTH) {
            throw new IllegalArgumentException("token length " + tokenLength);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + LENGTH_TOKEN);
        VarInt.write(out, LENGTH_NO_TOKEN + tokenLength);
        out.write(VERSION);
        out.write(tokenLength);
        if (tokenLength != 0) {
            out.write(token, 0, tokenLength);
        }
        return out.toByteArray();
    }

    /**
     * Incremental parser: bytes may arrive split across reads at any boundary. It consumes
     * only the preamble and leaves following bytes in the buffer for the caller.
     */
    public static final class Parser {
        private final VarInt.Reader length =
                new VarInt.Reader(MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, "13.2");
        private int bodyLength = -1;
        private byte[] body;
        private int filled;
        private int consumed;

        /**
         * Consumes preamble bytes from {@code data}. Returns true once complete; then
         * {@link #token()} is valid and further calls consume nothing.
         */
        public boolean feed(ByteBuffer data) throws MultistreamProtocolException {
            if (bodyLength >= 0 && filled == bodyLength) {
                return true;
            }
            while (bodyLength < 0) {
                if (!data.hasRemaining()) {
                    return false;
                }
                consumed++;
                if (length.feed(data.get() & 0xff)) {
                    bodyLength = length.value();
                    if (bodyLength != LENGTH_NO_TOKEN && bodyLength != LENGTH_TOKEN) {
                        throw new MultistreamProtocolException(
                                MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, "13.2",
                                "preamble length " + bodyLength);
                    }
                    body = new byte[bodyLength];
                }
            }
            int n = Math.min(bodyLength - filled, data.remaining());
            data.get(body, filled, n);
            filled += n;
            consumed += n;
            if (filled < bodyLength) {
                return false;
            }
            if ((body[0] & 0xff) != VERSION) {
                throw new MultistreamProtocolException(
                        MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, "13.2",
                        "preamble version " + (body[0] & 0xff));
            }
            int tokenLength = body[1] & 0xff;
            if ((tokenLength != 0 && tokenLength != TOKEN_LENGTH)
                    || bodyLength != LENGTH_NO_TOKEN + tokenLength) {
                throw new MultistreamProtocolException(
                        MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, "13.2",
                        "preamble token length " + tokenLength + " in " + bodyLength);
            }
            return true;
        }

        public boolean done() {
            return bodyLength >= 0 && filled == bodyLength;
        }

        /** The token, or null for none. Only valid once {@link #feed} returned true. */
        public byte[] token() {
            int tokenLength = body[1] & 0xff;
            if (tokenLength == 0) {
                return null;
            }
            byte[] token = new byte[tokenLength];
            System.arraycopy(body, 2, token, 0, tokenLength);
            return token;
        }

        /** Bytes consumed from the buffers fed so far. */
        public int consumed() {
            return consumed;
        }
    }
}
