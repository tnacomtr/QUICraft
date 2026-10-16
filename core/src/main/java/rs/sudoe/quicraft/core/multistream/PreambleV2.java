// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.multistream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Stream 0's version 2 preamble (docs/protocol.md 13.2):
 * {@code VarInt(length) || u8(version=2) || u8(token_length) || token || u8(min_shards) ||
 * u8(max_shards)}. Length is 4 or 20. Unknown version or length closes with {@code 0x1}. This is the
 * version-aware parser {@code /3} requires; it does not replace the v1
 * {@code transport.Preamble}.
 */
public final class PreambleV2 {
    public static final int VERSION = 2;
    public static final int TOKEN_LENGTH = 16;
    /** length with no token: version + token length. */
    public static final int LENGTH_NO_TOKEN = 4;
    /** length with a token: version + token length + 16-byte token. */
    public static final int LENGTH_TOKEN = 20;
    public static final int MAX_LENGTH = LENGTH_TOKEN;

    private PreambleV2() {}

    /** {@code token} null or empty for none, otherwise exactly 16 bytes. */
    public static byte[] encode(byte[] token) {
        return encode(token, 1, 6);
    }

    /** Encodes a client shard offer (13.2). */
    public static byte[] encode(byte[] token, int minShards, int maxShards) {
        int tokenLength = token == null ? 0 : token.length;
        if (tokenLength != 0 && tokenLength != TOKEN_LENGTH) {
            throw new IllegalArgumentException("token length " + tokenLength);
        }
        validateRange(minShards, maxShards);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 + LENGTH_TOKEN);
        VarInt.write(out, LENGTH_NO_TOKEN + tokenLength);
        out.write(VERSION);
        out.write(tokenLength);
        if (tokenLength != 0) {
            out.write(token, 0, tokenLength);
        }
        out.write(minShards);
        out.write(maxShards);
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
            if (minShards() < 1 || maxShards() > 6 || minShards() > maxShards()) {
                throw new MultistreamProtocolException(
                        MultistreamProtocolException.CLOSE_PROTOCOL_VIOLATION, "13.2",
                        "shard range " + minShards() + ".." + maxShards());
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

        /** Offered minimum shard count (13.2). */
        public int minShards() { return body[2 + (body[1] & 0xff)] & 0xff; }

        /** Offered maximum shard count (13.2). */
        public int maxShards() { return body[3 + (body[1] & 0xff)] & 0xff; }

        /**
         * Selects N (13.2): the configured preference clamped to the intersection of the client's
         * offered range and 1..{@code configuredMax}. An empty intersection is a typed
         * {@link MultistreamProtocolException#CLOSE_NO_COMMON_SHARDS} failure, never a silent clamp
         * outside the offer. Local misuse (bad configuration) throws IllegalArgumentException.
         */
        public int select(int configuredMax, int preference) throws MultistreamProtocolException {
            if (configuredMax < 1 || configuredMax > 6) throw new IllegalArgumentException("configured max 1..6");
            if (preference < 1 || preference > 6) throw new IllegalArgumentException("preference 1..6");
            int low = minShards(), high = Math.min(maxShards(), configuredMax);
            if (low > high) {
                throw new MultistreamProtocolException(MultistreamProtocolException.CLOSE_NO_COMMON_SHARDS,
                        "13.2", "no common shard count in offer " + low + ".." + maxShards());
            }
            return Math.max(low, Math.min(high, preference));
        }

        /** Bytes consumed from the buffers fed so far. */
        public int consumed() {
            return consumed;
        }
    }

    private static void validateRange(int min, int max) {
        if (min < 1 || max > 6 || min > max) throw new IllegalArgumentException("shard range");
    }
}
