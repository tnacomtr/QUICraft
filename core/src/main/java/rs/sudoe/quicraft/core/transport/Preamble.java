// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.transport;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The preamble at the start of each direction of stream 0 (docs/protocol.md §8): VarInt length,
 * version {@code 1}, token length (0 or 16), token. Client to server it carries an early token,
 * server to client a fresh one.
 */
final class Preamble {
    static final int VERSION = 1;
    static final int TOKEN_LENGTH = 16;
    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 64;

    private Preamble() {}

    /** {@code token} null or empty for none. */
    static byte[] encode(byte[] token) {
        int tokenLength = token == null ? 0 : token.length;
        if (tokenLength != 0 && tokenLength != TOKEN_LENGTH) {
            throw new IllegalArgumentException("token length " + tokenLength);
        }
        byte[] out = new byte[1 + 2 + tokenLength];
        out[0] = (byte) (2 + tokenLength); // VarInt, one byte while below 128
        out[1] = VERSION;
        out[2] = (byte) tokenLength;
        if (tokenLength != 0) {
            System.arraycopy(token, 0, out, 3, tokenLength);
        }
        return out;
    }

    /** The peer violated the preamble format: close with {@code 0x1}. */
    static final class InvalidPreambleException extends IOException {
        private static final long serialVersionUID = 1L;

        InvalidPreambleException(String message) {
            super(message);
        }
    }

    /** Reads one preamble from the front of the data, across as many chunks as it takes. */
    static final class Parser {
        private int length = -1;
        private int lengthShift;
        private int lengthValue;
        private byte[] body;
        private int filled;

        /**
         * Consumes preamble bytes from {@code data}, leaving what follows. Returns true once the
         * preamble is complete; then {@link #token()} is valid and further calls consume nothing.
         */
        boolean feed(ByteBuffer data) throws InvalidPreambleException {
            while (length < 0) {
                if (!data.hasRemaining()) {
                    return false;
                }
                int b = data.get() & 0xff;
                lengthValue |= (b & 0x7f) << lengthShift;
                lengthShift += 7;
                if ((b & 0x80) == 0) {
                    if (lengthValue < MIN_LENGTH || lengthValue > MAX_LENGTH) {
                        throw new InvalidPreambleException("preamble length " + lengthValue);
                    }
                    length = lengthValue;
                    body = new byte[length];
                } else if (lengthShift >= 14) {
                    throw new InvalidPreambleException("preamble length too long");
                }
            }
            int n = Math.min(length - filled, data.remaining());
            data.get(body, filled, n);
            filled += n;
            if (filled < length) {
                return false;
            }
            if (body[0] != VERSION) {
                throw new InvalidPreambleException("preamble version " + (body[0] & 0xff));
            }
            int tokenLength = body[1] & 0xff;
            if ((tokenLength != 0 && tokenLength != TOKEN_LENGTH) || length != 2 + tokenLength) {
                throw new InvalidPreambleException("preamble token length " + tokenLength + " in " + length);
            }
            return true;
        }

        boolean done() {
            return length >= 0 && filled == length;
        }

        /** The token, or null for none. Only once {@link #feed} returned true. */
        byte[] token() {
            int tokenLength = body[1] & 0xff;
            if (tokenLength == 0) {
                return null;
            }
            byte[] token = new byte[tokenLength];
            System.arraycopy(body, 2, token, 0, tokenLength);
            return token;
        }
    }
}
